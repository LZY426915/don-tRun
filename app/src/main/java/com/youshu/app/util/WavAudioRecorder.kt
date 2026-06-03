package com.youshu.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WavAudioRecorder(
    private val context: Context
) {
    private var audioRecord: AudioRecord? = null
    private var outputFile: File? = null
    private var recorderThread: Thread? = null
    private var finishLatch: CountDownLatch? = null
    private val recording = AtomicBoolean(false)
    @Volatile private var recordedBytes: Long = 0L

    val isRecording: Boolean
        get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(): File {
        check(!recording.get()) { "正在录音" }

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            SAMPLE_RATE
        )
        check(bufferSize > 0) { "无法初始化麦克风" }

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "无法初始化麦克风"
        }

        val dir = File(context.cacheDir, "agent_voice").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
        val latch = CountDownLatch(1)
        val buffer = ByteArray(bufferSize)
        recordedBytes = 0L
        audioRecord = record
        outputFile = file
        finishLatch = latch
        recording.set(true)

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            writeWavHeader(raf, 0)
        }

        record.startRecording()
        recorderThread = Thread({
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(WAV_HEADER_SIZE.toLong())
                    while (recording.get()) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            raf.write(buffer, 0, read)
                            recordedBytes += read.toLong()
                        }
                    }
                }
            } finally {
                latch.countDown()
            }
        }, "YoushuVoiceRecorder").also { it.start() }

        return file
    }

    fun stop(): File? {
        val record = audioRecord ?: return outputFile
        if (!recording.getAndSet(false)) return outputFile

        runCatching { record.stop() }
        runCatching { finishLatch?.await(1200, TimeUnit.MILLISECONDS) }
        runCatching { recorderThread?.join(300) }
        runCatching { record.release() }

        val file = outputFile
        audioRecord = null
        recorderThread = null
        finishLatch = null

        if (file != null && file.exists()) {
            RandomAccessFile(file, "rw").use { raf ->
                writeWavHeader(raf, recordedBytes)
            }
        }
        return file
    }

    private fun writeWavHeader(
        raf: RandomAccessFile,
        pcmDataSize: Long
    ) {
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE((36 + pcmDataSize).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1)
        raf.writeShortLE(1)
        raf.writeIntLE(SAMPLE_RATE)
        raf.writeIntLE(SAMPLE_RATE * BYTES_PER_SAMPLE)
        raf.writeShortLE(BYTES_PER_SAMPLE)
        raf.writeShortLE(BITS_PER_SAMPLE)
        raf.writeBytes("data")
        raf.writeIntLE(pcmDataSize.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val WAV_HEADER_SIZE = 44
        const val MIN_AUDIO_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 3
    }
}
