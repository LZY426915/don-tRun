package com.youshu.app.data.agent

import com.youshu.app.data.network.BackendStreamEvent
import com.youshu.app.data.network.BackendApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStreamAssemblerTest {
    @Test
    fun assemble_concatenatesTextAndFragmentedToolCalls() {
        val assembler = AgentStreamAssembler()
        assembler.accept(BackendStreamEvent.TextDelta("我来"))
        assembler.accept(BackendStreamEvent.TextDelta("查一下"))
        assembler.accept(BackendStreamEvent.ToolCallDelta(0, "call-", "find_", "{\"q\":"))
        assembler.accept(BackendStreamEvent.ToolCallDelta(0, "1", "items", "\"水\"}"))
        assembler.accept(BackendStreamEvent.Done("tool_calls"))

        assertEquals(
            AgentRound(
                text = "我来查一下",
                toolCalls = listOf(ToolCall("call-1", "find_items", "{\"q\":\"水\"}")),
                finishReason = "tool_calls"
            ),
            assembler.buildRound()
        )
        assertTrue(assembler.requiresVisibleTextReset)
    }

    @Test
    fun assemble_plainTextDoesNotRequireReset() {
        val assembler = AgentStreamAssembler()
        assembler.accept(BackendStreamEvent.TextDelta("正常回答"))
        assembler.accept(BackendStreamEvent.Done("stop"))

        assertEquals("正常回答", assembler.buildRound().text)
        assertFalse(assembler.requiresVisibleTextReset)
    }

    @Test
    fun buildCompletedRound_rejectsAStreamWithoutDone() {
        val assembler = AgentStreamAssembler()
        assembler.accept(BackendStreamEvent.TextDelta("只收到半截"))

        val error = assertThrows(BackendApiException::class.java) {
            assembler.buildCompletedRound()
        }

        assertEquals("STREAM_INTERRUPTED", error.code)
    }
}
