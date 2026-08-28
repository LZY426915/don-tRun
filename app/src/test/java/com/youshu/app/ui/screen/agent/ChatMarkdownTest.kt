package com.youshu.app.ui.screen.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun boldMarkers_areRemovedAndRepresentedAsBoldSegments() {
        val segments = parseChatMarkdown("已经找到 **华为耳机**，在床头柜。")

        assertEquals("已经找到 华为耳机，在床头柜。", segments.joinToString("") { it.text })
        assertFalse(segments.joinToString("") { it.text }.contains("**"))
        assertTrue(segments.single { it.text == "华为耳机" }.bold)
    }
}
