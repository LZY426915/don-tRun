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

    fun stop(message: ChatMessage): ChatMessage = message.copy(
        content = message.content.ifBlank { "已停止生成。" },
        status = ChatMessageStatus.STOPPED
    )

    fun fail(message: ChatMessage, safeMessage: String): ChatMessage = message.copy(
        content = safeMessage.ifBlank { "小东西暂时没有连上 AI 服务，请稍后重试。" },
        status = ChatMessageStatus.ERROR
    )
}
