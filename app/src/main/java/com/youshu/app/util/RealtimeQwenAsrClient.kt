package com.youshu.app.util

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.youshu.app.data.network.QwenRealtimeToken
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * One Qwen realtime session per voice hold. The microphone remains owned by
 * WavAudioRecorder; this class only sends the PCM copies it receives.
 */
class RealtimeQwenAsrClient(
    private val tokenProvider: suspend () -> QwenRealtimeToken,
    private val webSocketClient: OkHttpClient = defaultWebSocketClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var activeSession: Session? = null

    fun start(
        onPartialText: (String) -> Unit,
        onFinalText: (String) -> Unit,
        onError: (Throwable) -> Unit = {}
    ): Boolean {
        val session = Session(onPartialText, onFinalText, onError)
        synchronized(lock) {
            if (activeSession != null) return false
            activeSession = session
        }

        Log.d(TAG, "session connecting")
        scope.launch {
            try {
                val token = tokenProvider()
                if (!isActive(session)) return@launch
                val request = Request.Builder()
                    .url(token.websocketUrl)
                    .header("Authorization", "Bearer ${token.token}")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .build()
                webSocketClient.newWebSocket(request, Listener(session))
            } catch (error: Throwable) {
                fail(session, error)
            }
        }
        return true
    }

    fun sendPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val session = synchronized(lock) { activeSession } ?: return
        var socketToUse: WebSocket? = null
        synchronized(session) {
            if (session.unavailable) return
            if (session.socketOpen) {
                socketToUse = session.socket
            } else if (session.pendingChunks.size < MAX_PENDING_CHUNKS) {
                session.pendingChunks.addLast(pcm.copyOf())
            }
        }
        socketToUse?.let { sendAudioChunk(session, it, pcm) }
    }

    /**
     * Stops the realtime stream and returns only Qwen's completed transcript.
     * A null result means the caller must use the existing WAV batch fallback.
     */
    suspend fun finish(): String? {
        val session = synchronized(lock) { activeSession } ?: return null
        synchronized(session) { session.finishRequested = true }

        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { session.connected.await() }
        val socket = synchronized(session) {
            if (session.unavailable || !session.socketOpen) null else session.socket
        }
        if (socket != null) {
            flushPending(session, socket)
            val sent = socket.send(
                JSONObject()
                    .put("event_id", UUID.randomUUID().toString())
                    .put("type", "session.finish")
                    .toString()
            )
            if (sent) Log.d(TAG, "session finish sent")
        }

        withTimeoutOrNull(FINISH_TIMEOUT_MS) { session.finished.await() }
        val result = synchronized(session) { session.finalTranscript.trim() }
        cleanup(session)
        Log.d(TAG, "session finished finalTextLength=${result.length}")
        return result.takeIf { it.isNotBlank() }
    }

    fun cancel() {
        val session = synchronized(lock) { activeSession } ?: return
        synchronized(lock) {
            if (activeSession === session) activeSession = null
        }
        synchronized(session) {
            session.unavailable = true
            session.pendingChunks.clear()
            session.socketOpen = false
            session.connected.complete(false)
            session.finished.complete(Unit)
        }
        session.socket?.cancel()
        Log.d(TAG, "session cancelled")
    }

    private fun handleOpen(session: Session, socket: WebSocket) {
        if (!isActive(session)) {
            socket.cancel()
            return
        }
        synchronized(session) {
            session.socket = socket
            session.socketOpen = true
            session.connected.complete(true)
        }
        Log.d(TAG, "session connected")
        val update = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("type", "session.update")
            .put(
                "session",
                JSONObject()
                    .put("input_audio_format", "pcm")
                    .put("sample_rate", WavAudioRecorder.SAMPLE_RATE)
                    .put(
                        "input_audio_transcription",
                        JSONObject().put("language", "zh")
                    )
                    .put(
                        "turn_detection",
                        JSONObject()
                            .put("type", "server_vad")
                            .put("threshold", 0.0)
                            .put("silence_duration_ms", 400)
                    )
            )
            .toString()
        if (!socket.send(update)) {
            fail(session, IllegalStateException("Qwen realtime session.update was rejected"))
            return
        }
        flushPending(session, socket)
        if (synchronized(session) { session.finishRequested }) {
            socket.send(
                JSONObject()
                    .put("event_id", UUID.randomUUID().toString())
                    .put("type", "session.finish")
                    .toString()
            )
        }
    }

    private fun flushPending(session: Session, socket: WebSocket) {
        while (true) {
            val chunk = synchronized(session) {
                if (session.pendingChunks.isEmpty()) null else session.pendingChunks.removeFirst()
            } ?: break
            sendAudioChunk(session, socket, chunk)
        }
    }

    private fun sendAudioChunk(session: Session, socket: WebSocket, pcm: ByteArray) {
        if (!isActive(session)) return
        val sent = socket.send(
            JSONObject()
                .put("event_id", UUID.randomUUID().toString())
                .put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
                .toString()
        )
        if (!sent) {
            fail(session, IllegalStateException("Qwen realtime audio chunk was rejected"))
            return
        }
        synchronized(session) { session.chunkCount += 1 }
        val count = synchronized(session) { session.chunkCount }
        if (count == 1 || count % 20 == 0) {
            Log.d(TAG, "audio chunk sent count=$count bytes=${pcm.size}")
        }
    }

    private fun handleMessage(session: Session, text: String) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            fail(session, IllegalStateException("Invalid Qwen realtime event", it))
            return
        }
        when (event.optString("type")) {
            "conversation.item.input_audio_transcription.text" -> {
                val preview = (event.optString("text") + event.optString("stash")).trim()
                if (preview.isNotBlank()) {
                    val display = synchronized(session) {
                        (session.finalTranscript + preview).trim()
                    }
                    val changed = synchronized(session) {
                        if (session.lastPreview == display) false
                        else {
                            session.lastPreview = display
                            true
                        }
                    }
                    if (changed) {
                        Log.d(TAG, "partial/realtime text=$display")
                        dispatchToMain(session) { session.onPartialText(display) }
                    }
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val finalText = event.optString("transcript").trim()
                if (finalText.isNotBlank()) {
                    val completeText = synchronized(session) {
                        val itemId = event.optString("item_id").trim()
                        if (itemId.isNotBlank()) {
                            session.completedItems[itemId] = finalText
                        } else {
                            session.anonymousCompletedItems += finalText
                        }
                        session.finalTranscript =
                            (session.completedItems.values + session.anonymousCompletedItems)
                                .joinToString(separator = "")
                        session.finalTranscript
                    }
                    Log.d(TAG, "final text=$completeText")
                    dispatchToMain(session) { session.onFinalText(completeText) }
                }
            }

            "session.finished" -> {
                synchronized(session) { session.finished.complete(Unit) }
                Log.d(TAG, "session finished event received")
            }

            "error" -> {
                val message = event.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Qwen realtime ASR returned an error"
                fail(session, IllegalStateException(message))
            }
        }
    }

    private fun fail(session: Session, error: Throwable) {
        if (!isActive(session)) return
        synchronized(session) {
            session.unavailable = true
            session.socketOpen = false
            session.pendingChunks.clear()
            session.connected.complete(false)
            session.finished.complete(Unit)
        }
        Log.e(TAG, "session failed: ${error.message}")
        dispatchToMain(session) { session.onError(error) }
    }

    private fun cleanup(session: Session) {
        synchronized(lock) {
            if (activeSession === session) activeSession = null
        }
        synchronized(session) {
            session.socketOpen = false
            session.pendingChunks.clear()
        }
        session.socket?.close(1000, "voice session finished")
    }

    private fun dispatchToMain(session: Session, action: () -> Unit) {
        mainHandler.post {
            if (isActive(session)) action()
        }
    }

    private fun isActive(session: Session): Boolean = synchronized(lock) { activeSession === session }

    private inner class Listener(private val session: Session) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            handleOpen(session, webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(session, text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(session, t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!synchronized(session) { session.finishRequested }) {
                fail(session, IllegalStateException("Qwen realtime socket closed: $code $reason"))
            }
        }
    }

    private class Session(
        val onPartialText: (String) -> Unit,
        val onFinalText: (String) -> Unit,
        val onError: (Throwable) -> Unit
    ) {
        val pendingChunks = ArrayDeque<ByteArray>()
        val connected = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        var socketOpen = false
        var finishRequested = false
        var unavailable = false
        var chunkCount = 0
        var lastPreview = ""
        var finalTranscript = ""
        val completedItems = LinkedHashMap<String, String>()
        val anonymousCompletedItems = mutableListOf<String>()
    }

    private companion object {
        const val TAG = "VoiceRealtimeASR"
        const val MAX_PENDING_CHUNKS = 96
        const val CONNECT_TIMEOUT_MS = 2_000L
        const val FINISH_TIMEOUT_MS = 2_500L

        fun defaultWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
