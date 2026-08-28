package com.youshu.app.ui.screen.agent

internal data class ChatTextSegment(
    val text: String,
    val bold: Boolean
)

internal fun parseChatMarkdown(value: String): List<ChatTextSegment> {
    if (value.isEmpty()) return listOf(ChatTextSegment("", bold = false))
    val segments = mutableListOf<ChatTextSegment>()
    var cursor = 0
    var bold = false
    while (cursor < value.length) {
        val marker = value.indexOf("**", cursor)
        if (marker < 0) {
            segments += ChatTextSegment(value.substring(cursor), bold)
            break
        }
        if (marker > cursor) {
            segments += ChatTextSegment(value.substring(cursor, marker), bold)
        }
        bold = !bold
        cursor = marker + 2
    }
    if (segments.isEmpty()) return listOf(ChatTextSegment(value.replace("**", ""), bold = false))
    return segments
}
