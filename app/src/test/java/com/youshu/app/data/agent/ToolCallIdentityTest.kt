package com.youshu.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallIdentityTest {
    @Test
    fun identity_ignoresJsonWhitespaceAndObjectKeyOrder() {
        val first = ToolCall(
            id = "call-1",
            name = "add_location",
            arguments = """{"name":"书桌","parent_location":"办公室"}"""
        )
        val sameMeaning = ToolCall(
            id = "call-2",
            name = "add_location",
            arguments = """{ "parent_location" : "办公室", "name" : "书桌" }"""
        )

        assertEquals(toolCallIdentity(first), toolCallIdentity(sameMeaning))
    }
}
