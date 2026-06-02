package com.youshu.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youshu.app.data.agent.AgentClient
import com.youshu.app.data.agent.ChatConversation
import com.youshu.app.data.agent.ChatHistoryService
import com.youshu.app.data.agent.ChatMessage
import com.youshu.app.data.agent.ChatMessageStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentClient: AgentClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

    private val _activeConversation = MutableStateFlow<ChatConversation?>(null)
    val activeConversation: StateFlow<ChatConversation?> = _activeConversation.asStateFlow()

    private val _isReplying = MutableStateFlow(false)
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _historyVisible = MutableStateFlow(false)
    val historyVisible: StateFlow<Boolean> = _historyVisible.asStateFlow()

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    companion object {
        /** 最多保留的对话数量 */
        private const val MAX_CONVERSATIONS = 50
        /** 每条对话最多保留的消息数量 */
        private const val MAX_MESSAGES_PER_CONVERSATION = 200
    }

    init {
        loadConversations()
        enforceStorageLimits()
    }

    // ──────────────────────────────────────────
    // 数据加载
    // ──────────────────────────────────────────

    fun loadConversations() {
        viewModelScope.launch {
            val loaded = ChatHistoryService.getConversations(context)
            val initial = if (loaded.isEmpty()) {
                val created = ChatHistoryService.createConversation(context)
                listOf(created)
            } else {
                loaded
            }
            _conversations.value = initial
            _activeConversation.value = initial.firstOrNull()
        }
    }

    // ──────────────────────────────────────────
    // 对话管理
    // ──────────────────────────────────────────

    fun startNewConversation() {
        viewModelScope.launch {
            val conversation = ChatHistoryService.createConversation(context)
            _conversations.update { current ->
                listOf(conversation) + current
            }
            _activeConversation.value = conversation
            _historyVisible.value = false
        }
    }

    fun selectConversation(conversation: ChatConversation) {
        _activeConversation.value = conversation
        _historyVisible.value = false
    }

    fun deleteConversation(conversation: ChatConversation) {
        viewModelScope.launch {
            ChatHistoryService.deleteConversation(context, conversation.id)
            val remaining = _conversations.value.filterNot { it.id == conversation.id }
            if (remaining.isEmpty()) {
                val fresh = ChatHistoryService.createConversation(context)
                _conversations.value = listOf(fresh)
                _activeConversation.value = fresh
            } else {
                _conversations.value = remaining
                if (_activeConversation.value?.id == conversation.id) {
                    _activeConversation.value = remaining.first()
                }
            }
        }
    }

    // ──────────────────────────────────────────
    // 发送消息
    // ──────────────────────────────────────────

    fun submitTextMessage(message: String) {
        val current = _activeConversation.value ?: return
        val trimmed = message.trim()
        if (trimmed.isEmpty() || _isReplying.value) return

        val userMessage = ChatHistoryService.createUserMessage(content = trimmed)
        val firstUserMessage = current.messages.none { it.role == com.youshu.app.data.agent.ChatRole.USER }
        val conversationWithUser = current.copy(
            title = if (firstUserMessage || current.title == ChatHistoryService.DEFAULT_TITLE) {
                ChatHistoryService.titleFromMessage(trimmed)
            } else {
                current.title
            },
            updatedAt = userMessage.createdAt,
            messages = current.messages + userMessage
        )

        _isReplying.value = true
        replaceConversation(conversationWithUser)

        viewModelScope.launch {
            // 持久化用户消息
            ChatHistoryService.saveConversation(context, conversationWithUser)

            // 调用真实 AI
            val history = conversationWithUser.messages
            val result = agentClient.sendMessage(
                history = history,
                newMessage = trimmed
            )

            val (replyContent, replyStatus) = result.fold(
                onSuccess = { content -> content to ChatMessageStatus.NORMAL },
                onFailure = { error ->
                    val friendlyMessage = when {
                        error.message?.contains("API Key") == true ||
                        error.message?.contains("模型") == true -> error.message!!
                        error.message?.contains("请先在") == true -> error.message!!
                        else -> "小东西暂时没有连上 AI 服务，请稍后重试。"
                    }
                    friendlyMessage to ChatMessageStatus.ERROR
                }
            )

            val assistantMessage = ChatHistoryService.createAssistantMessage(
                content = replyContent,
                status = replyStatus
            )
            val conversationWithAssistant = conversationWithUser.copy(
                updatedAt = assistantMessage.createdAt,
                messages = conversationWithUser.messages + assistantMessage
            )
            replaceConversation(conversationWithAssistant)
            ChatHistoryService.saveConversation(context, conversationWithAssistant)
            _isReplying.value = false
        }
    }

    fun submitImageMessage(source: String) {
        val current = _activeConversation.value ?: return
        if (_isReplying.value) return

        val userMessage = ChatHistoryService.createUserMessage(
            content = "$source 图片",
            imageUri = "mock://agent-image/${source}/${System.currentTimeMillis()}"
        )
        val firstUserMessage = current.messages.none { it.role == com.youshu.app.data.agent.ChatRole.USER }
        val conversationWithImage = current.copy(
            title = if (firstUserMessage || current.title == ChatHistoryService.DEFAULT_TITLE) {
                ChatHistoryService.titleFromMessage("$source 图片识别")
            } else {
                current.title
            },
            updatedAt = userMessage.createdAt,
            messages = current.messages + userMessage
        )

        _isReplying.value = true
        replaceConversation(conversationWithImage)

        viewModelScope.launch {
            ChatHistoryService.saveConversation(context, conversationWithImage)

            // 图片消息暂时使用文本模式告知用户
            val assistantMessage = ChatHistoryService.createAssistantMessage(
                content = "图片识别功能正在接入中，暂时还无法分析图片内容。你可以先用文字告诉我你想找什么，我会尽力帮忙！",
                status = ChatMessageStatus.NORMAL
            )
            val conversationWithAssistant = conversationWithImage.copy(
                updatedAt = assistantMessage.createdAt,
                messages = conversationWithImage.messages + assistantMessage
            )
            replaceConversation(conversationWithAssistant)
            ChatHistoryService.saveConversation(context, conversationWithAssistant)
            _isReplying.value = false
        }
    }

    // ──────────────────────────────────────────
    // UI 状态
    // ──────────────────────────────────────────

    fun setHistoryVisible(visible: Boolean) {
        _historyVisible.value = visible
    }

    fun setSearchKeyword(keyword: String) {
        _searchKeyword.value = keyword
    }

    // ──────────────────────────────────────────
    // 内部辅助
    // ──────────────────────────────────────────

    /**
     * 替换对话列表中的某条对话，并自动裁剪消息和对话总数。
     */
    private fun replaceConversation(conversation: ChatConversation) {
        // 裁剪单条对话的消息数
        val trimmed = if (conversation.messages.size > MAX_MESSAGES_PER_CONVERSATION) {
            val excess = conversation.messages.size - MAX_MESSAGES_PER_CONVERSATION
            conversation.copy(
                messages = conversation.messages.drop(excess)
            )
        } else {
            conversation
        }

        _activeConversation.value = trimmed
        _conversations.update { current ->
            val updated = current.filterNot { it.id == trimmed.id }
                .plus(trimmed)
                .sortedByDescending { it.updatedAt }

            // 裁剪对话总数：保留最近更新的 MAX_CONVERSATIONS 条
            if (updated.size > MAX_CONVERSATIONS) {
                updated.take(MAX_CONVERSATIONS)
            } else {
                updated
            }
        }
    }

    /**
     * 启动时清理超限的旧对话。
     */
    private fun enforceStorageLimits() {
        viewModelScope.launch {
            val all = ChatHistoryService.getConversations(context)
            if (all.size > MAX_CONVERSATIONS) {
                val toDelete = all.drop(MAX_CONVERSATIONS)
                toDelete.forEach { conversation ->
                    ChatHistoryService.deleteConversation(context, conversation.id)
                }
            }
        }
    }
}
