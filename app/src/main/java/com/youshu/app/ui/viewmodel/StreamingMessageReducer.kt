package com.youshu.app.ui.viewmodel

import com.youshu.app.data.agent.ChatMessage
import com.youshu.app.data.agent.ChatMessageStatus

internal object StreamingMessageReducer {
    fun append(message: ChatMessage, text: String): ChatMessage = message.copy(
        content = message.content + text,
        status = ChatMessageStatus.LOADING
    )

    fun reset(message: ChatMessage): ChatMessage = message.copy(
        content = "",
        status = ChatMessageStatus.LOADING
    )

    fun complete(message: ChatMessage): ChatMessage = message.copy(
        content = message.content.ifBlank { "AI 没有返回可用内容，请稍后重试。" },
        status = if (message.content.isBlank()) ChatMessageStatus.ERROR else ChatMessageStatus.NORMAL
    )

    fun stop(message: ChatMessage, operationSummary: String? = null): ChatMessage {
        val partial = message.content.trimEnd()
        val verifiedOperation = operationSummary?.trim().orEmpty()
        val content = when {
            verifiedOperation.isNotBlank() && partial.isNotBlank() ->
                "$partial\n\n操作已完成：$verifiedOperation\n（已停止继续生成）"
            verifiedOperation.isNotBlank() ->
                "操作已完成：$verifiedOperation\n（已停止继续生成）"
            partial.isNotBlank() -> partial
            else -> "已停止生成。"
        }
        return message.copy(content = content, status = ChatMessageStatus.STOPPED)
    }

    fun fail(message: ChatMessage, safeMessage: String): ChatMessage {
        val fallback = safeMessage.ifBlank { "小东西暂时没有连上 AI 服务，请稍后重试。" }
        val partial = message.content.trimEnd()
        val content = if (partial.isBlank()) fallback else "$partial\n\n（回复中断：$fallback）"
        return message.copy(content = content, status = ChatMessageStatus.ERROR)
    }
}
