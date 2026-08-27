package com.youshu.app.ui.viewmodel

import com.youshu.app.data.agent.ChatConversation

internal object StreamingConversationState {
    fun replaceExisting(
        conversations: List<ChatConversation>,
        updated: ChatConversation
    ): List<ChatConversation> {
        if (conversations.none { it.id == updated.id }) return conversations
        return conversations
            .map { if (it.id == updated.id) updated else it }
            .sortedByDescending { it.updatedAt }
    }

    fun updateActive(
        active: ChatConversation?,
        updated: ChatConversation
    ): ChatConversation? = if (active?.id == updated.id) updated else active
}
