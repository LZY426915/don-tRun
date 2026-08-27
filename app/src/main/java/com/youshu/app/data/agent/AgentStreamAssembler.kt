package com.youshu.app.data.agent

import com.youshu.app.data.network.BackendStreamEvent

internal data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

internal data class AgentRound(
    val text: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?
)

internal class AgentStreamAssembler {
    private val text = StringBuilder()
    private val toolCalls = linkedMapOf<Int, MutableToolCall>()
    private var finishReason: String? = null

    val requiresVisibleTextReset: Boolean
        get() = text.isNotEmpty() && toolCalls.isNotEmpty()

    fun accept(event: BackendStreamEvent) {
        when (event) {
            is BackendStreamEvent.TextDelta -> text.append(event.text)
            is BackendStreamEvent.ToolCallDelta -> {
                val call = toolCalls.getOrPut(event.index) { MutableToolCall() }
                event.id?.let(call.id::append)
                event.name?.let(call.name::append)
                event.arguments?.let(call.arguments::append)
            }
            is BackendStreamEvent.Done -> finishReason = event.finishReason
        }
    }

    fun buildRound(): AgentRound = AgentRound(
        text = text.toString(),
        toolCalls = toolCalls.values.map {
            ToolCall(
                id = it.id.toString(),
                name = it.name.toString(),
                arguments = it.arguments.toString().ifBlank { "{}" }
            )
        },
        finishReason = finishReason
    )

    private class MutableToolCall {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }
}
