package com.youshu.app.data.network

sealed interface BackendStreamEvent {
    data class TextDelta(val text: String) : BackendStreamEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?
    ) : BackendStreamEvent

    data class Done(val finishReason: String?) : BackendStreamEvent
}
