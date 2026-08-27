package com.youshu.app.data.network

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSseReaderTest {
    private val reader = BackendSseReader()

    @Test
    fun read_ignoresCommentsAndDecodesEveryEventType() {
        val source = Buffer().writeUtf8(
            """
            : keep-alive

            event: text-delta
            data: {"text":"你好"}

            event: tool-call-delta
            data: {"index":0,"id":"call-1","name":"list_","arguments":"{\"q\":"}

            event: tool-call-delta
            data: {"index":0,"name":"items","arguments":"\"水\"}"}

            event: done
            data: {"finishReason":"tool_calls"}

            """.trimIndent()
        )

        assertEquals(
            listOf(
                BackendStreamEvent.TextDelta("你好"),
                BackendStreamEvent.ToolCallDelta(0, "call-1", "list_", "{\"q\":"),
                BackendStreamEvent.ToolCallDelta(0, null, "items", "\"水\"}"),
                BackendStreamEvent.Done("tool_calls")
            ),
            reader.read(source).toList()
        )
    }

    @Test
    fun read_joinsRepeatedDataLinesAndFlushesAtEof() {
        val source = Buffer().writeUtf8(
            """
            event: text-delta
            data: {
            data: "text":"末尾"}
            """.trimIndent()
        )

        assertEquals(
            listOf(BackendStreamEvent.TextDelta("末尾")),
            reader.read(source).toList()
        )
    }

    @Test
    fun read_throwsSanitizedBackendErrorFrame() {
        val source = Buffer().writeUtf8(
            """
            event: error
            data: {"code":"PROVIDER_RATE_LIMITED","message":"AI 服务繁忙，请稍后重试。","retryable":true,"requestId":"req-1"}

            """.trimIndent()
        )

        val failure = runCatching { reader.read(source).toList() }.exceptionOrNull()
            as BackendApiException
        assertEquals("PROVIDER_RATE_LIMITED", failure.code)
        assertEquals("AI 服务繁忙，请稍后重试。", failure.safeMessage)
        assertEquals("req-1", failure.requestId)
        assertTrue(failure.retryable)
    }

    @Test
    fun read_rejectsMalformedJsonAndUnknownEventNames() {
        for (payload in listOf(
            "event: text-delta\ndata: not-json\n\n",
            "event: surprise\ndata: {}\n\n"
        )) {
            val failure = runCatching {
                reader.read(Buffer().writeUtf8(payload)).toList()
            }.exceptionOrNull() as BackendApiException
            assertEquals("INVALID_RESPONSE", failure.code)
            assertTrue(failure.retryable)
            assertFalse(failure.safeMessage.contains("not-json"))
        }
    }
}
