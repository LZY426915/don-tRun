package com.youshu.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTextTest {
    @Test
    fun dsmlToolCall_isParsedAndRemovedFromVisibleText() {
        val text = """
            我来处理。
            < | | DSML | | tool_calls>
            < | | DSML | | invoke name="update_item_location">
            < | | DSML | | parameter name="name" string="true">华为蓝牙耳机充电仓</ | | DSML | | parameter>
            < | | DSML | | parameter name="new_parent" string="true">卧室</ | | DSML | | parameter>
            < | | DSML | | parameter name="new_location" string="true">床头柜</ | | DSML | | parameter>
            < / | | DSML | | invoke>
            < / | | DSML | | tool_calls>
        """.trimIndent()

        val parsed = AgentProtocolText.parse(text)

        assertEquals("我来处理。", parsed.visibleText)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("update_item_location", parsed.toolCalls.single().name)
        assertTrue(parsed.toolCalls.single().arguments.contains("华为蓝牙耳机充电仓"))
        assertTrue(parsed.toolCalls.single().arguments.contains("\"target_location\":\"卧室 / 床头柜\""))
    }

    @Test
    fun streamGuard_hidesAProtocolMarkerSplitAcrossChunks() {
        val guard = AgentVisibleTextGuard()

        assertEquals("正常回复", guard.accept("正常回复< | | DS"))
        assertEquals("", guard.accept("ML | | tool_calls>乱码"))
        assertTrue(guard.protocolDetected)
    }

    @Test
    fun dsmlLookup_mapsKeywordBodyToTheQueryArgument() {
        val parsed = AgentProtocolText.parse(
            """
                < | | DSML | | tool_calls>
                < | | DSML | | invoke name="find_related_items">
                < | | DSML | | parameter name="keyword" string="true">华为耳机</ | | DSML | | parameter>
                < / | | DSML | | invoke>
                < / | | DSML | | tool_calls>
            """.trimIndent()
        )

        assertEquals("find_related_items", parsed.toolCalls.single().name)
        assertTrue(parsed.toolCalls.single().arguments.contains("\"query\":\"华为耳机\""))
    }
}
