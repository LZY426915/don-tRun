package com.youshu.app.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.BufferedSource

internal class BackendSseReader(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun read(source: BufferedSource): Sequence<BackendStreamEvent> = sequence {
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        fun clearFrame() {
            eventName = null
            dataLines.clear()
        }

        suspend fun SequenceScope<BackendStreamEvent>.emitFrame() {
            if (eventName == null && dataLines.isEmpty()) return
            val name = eventName ?: throw invalidResponse()
            val payload = parsePayload(dataLines.joinToString("\n"))
            when (name) {
                "text-delta" -> yield(
                    BackendStreamEvent.TextDelta(
                        payload.string("text") ?: throw invalidResponse()
                    )
                )
                "tool-call-delta" -> yield(
                    BackendStreamEvent.ToolCallDelta(
                        index = payload["index"]?.jsonPrimitive?.intOrNull
                            ?: throw invalidResponse(),
                        id = payload.string("id"),
                        name = payload.string("name"),
                        arguments = payload.string("arguments")
                    )
                )
                "done" -> yield(BackendStreamEvent.Done(payload.string("finishReason")))
                "error" -> throw BackendApiException(
                    code = payload.string("code") ?: "BACKEND_ERROR",
                    safeMessage = payload.string("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: "AI 服务暂时不可用，请稍后重试。",
                    retryable = payload["retryable"]?.jsonPrimitive?.booleanOrNull == true,
                    requestId = payload.string("requestId")
                )
                else -> throw invalidResponse()
            }
        }

        while (true) {
            val line = source.readUtf8Line()
            if (line == null) {
                emitFrame()
                break
            }
            when {
                line.isEmpty() -> {
                    emitFrame()
                    clearFrame()
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> {
                    eventName = line.substringAfter(':').removePrefix(" ")
                }
                line == "data" -> dataLines += ""
                line.startsWith("data:") -> {
                    dataLines += line.substringAfter(':').removePrefix(" ")
                }
            }
        }
    }

    private fun parsePayload(data: String): JsonObject = runCatching {
        json.parseToJsonElement(data).jsonObject
    }.getOrElse { throw invalidResponse(it) }

    private fun JsonObject.string(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun invalidResponse(cause: Throwable? = null) = BackendApiException(
        code = "INVALID_RESPONSE",
        safeMessage = "服务返回的数据格式异常，请稍后重试。",
        retryable = true,
        cause = cause
    )
}
