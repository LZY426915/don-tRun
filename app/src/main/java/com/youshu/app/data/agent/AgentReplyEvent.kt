package com.youshu.app.data.agent

sealed interface AgentReplyEvent {
    data class AppendText(val text: String) : AgentReplyEvent
    data class OperationCommitted(val summary: String) : AgentReplyEvent
    data object ResetText : AgentReplyEvent
    data object Completed : AgentReplyEvent
}
