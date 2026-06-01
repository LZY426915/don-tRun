package com.youshu.app.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class ChatRole {
    USER,
    ASSISTANT
}

@Serializable
enum class ChatMessageStatus {
    NORMAL,
    LOADING,
    ERROR
}

@Serializable
data class AgentItemCard(
    val id: String,
    val name: String,
    val location: String,
    val category: String,
    val quantity: String,
    val note: String = "",
    val expireHint: String? = null,
    val imageUri: String? = null
)

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val imageUri: String? = null,
    val status: ChatMessageStatus = ChatMessageStatus.NORMAL,
    val itemCards: List<AgentItemCard> = emptyList()
)

@Serializable
data class ChatConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList()
)

object ChatHistoryService {
    const val DEFAULT_TITLE = "新的对话"
    const val WELCOME_TEXT = "你好，我是小东西，帮你管好每件物品不弄丢。你可以问我：冰箱里有什么快过期？我的充电器放在哪？帮我整理一份购物清单。"

    private const val PREFS_NAME = "agent_chat_history"
    private const val KEY_CONVERSATIONS = "conversations"
    private const val TITLE_MAX_LENGTH = 14

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun getConversations(context: Context): List<ChatConversation> = withContext(Dispatchers.IO) {
        readConversations(context).sortedByDescending { it.updatedAt }
    }

    suspend fun saveConversation(context: Context, conversation: ChatConversation) {
        withContext(Dispatchers.IO) {
            val conversations = readConversations(context)
                .filterNot { it.id == conversation.id }
                .plus(conversation)
                .sortedByDescending { it.updatedAt }
            writeConversations(context, conversations)
        }
    }

    suspend fun deleteConversation(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            val conversations = readConversations(context).filterNot { it.id == id }
            writeConversations(context, conversations)
        }
    }

    suspend fun createConversation(context: Context): ChatConversation {
        val conversation = newConversation()
        saveConversation(context, conversation)
        return conversation
    }

    fun newConversation(now: Long = System.currentTimeMillis()): ChatConversation {
        return ChatConversation(
            id = UUID.randomUUID().toString(),
            title = DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            messages = listOf(createWelcomeMessage(now))
        )
    }

    fun createWelcomeMessage(now: Long = System.currentTimeMillis()): ChatMessage {
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            content = WELCOME_TEXT,
            createdAt = now
        )
    }

    fun createUserMessage(
        content: String,
        now: Long = System.currentTimeMillis(),
        imageUri: String? = null
    ): ChatMessage {
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = content,
            createdAt = now,
            imageUri = imageUri
        )
    }

    fun createAssistantMessage(
        content: String,
        now: Long = System.currentTimeMillis(),
        imageUri: String? = null,
        status: ChatMessageStatus = ChatMessageStatus.NORMAL,
        itemCards: List<AgentItemCard> = emptyList()
    ): ChatMessage {
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.ASSISTANT,
            content = content,
            createdAt = now,
            imageUri = imageUri,
            status = status,
            itemCards = itemCards
        )
    }

    fun titleFromMessage(content: String): String {
        val compact = content.replace(Regex("\\s+"), " ").trim()
        if (compact.isEmpty()) return DEFAULT_TITLE
        return if (compact.length <= TITLE_MAX_LENGTH) {
            compact
        } else {
            compact.take(TITLE_MAX_LENGTH) + "..."
        }
    }

    private fun readConversations(context: Context): List<ChatConversation> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONVERSATIONS, null)
            ?: return emptyList()

        return runCatching {
            json.decodeFromString(ListSerializer(ChatConversation.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun writeConversations(context: Context, conversations: List<ChatConversation>) {
        val raw = json.encodeToString(ListSerializer(ChatConversation.serializer()), conversations)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONVERSATIONS, raw)
            .apply()
    }
}
