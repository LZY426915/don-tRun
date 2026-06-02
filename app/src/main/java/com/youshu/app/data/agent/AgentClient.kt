package com.youshu.app.data.agent

import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.repository.AiModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentClient @Inject constructor(
    private val aiModelRepository: AiModelRepository,
    private val inventoryTool: InventoryAgentTool
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    // ──────────────────────────────────────────
    // 公开方法
    // ──────────────────────────────────────────

    /**
     * 发送纯文本消息到 DeepSeek，返回 AI 的文本回复。
     * 内部自动处理 function calling：如果 AI 需要查询数据库，会先执行本地工具再生成回复。
     */
    suspend fun sendMessage(
        history: List<ChatMessage>,
        newMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig()

            // 发送消息，带工具调用循环（最多 MAX_TOOL_ROUNDS 轮）
            var currentResponse = executeApiCall(
                config,
                buildRequestJson(
                    config = config,
                    history = history,
                    newMessage = newMessage,
                    includeTools = true
                )
            )
            var lastToolResults: List<Pair<ToolCall, String>> = emptyList()

            for (round in 1..MAX_TOOL_ROUNDS) {
                val toolCalls = extractToolCalls(currentResponse)
                if (toolCalls.isEmpty()) break // AI 直接返回了文本，结束

                // 执行所有工具调用
                val toolResults = toolCalls.map { tc -> tc to executeToolCall(tc) }
                lastToolResults = toolResults

                // 将工具结果送回 AI
                currentResponse = executeApiCall(
                    config,
                    buildToolResultRequest(
                        config = config,
                        history = history,
                        newMessage = newMessage,
                        assistantMessage = extractMessageJson(currentResponse),
                        toolResults = toolResults
                    )
                )
            }

            extractContentOrNull(currentResponse)
                ?: buildToolFallbackAnswer(newMessage, lastToolResults)
                ?: error("AI 暂时没有组织出回复，但本地工具也没有查到可用结果。请换个说法再试一次。")
        }
    }

    /**
     * 发送图片消息（多模态），暂不启用工具调用。
     */
    suspend fun sendImageMessage(
        history: List<ChatMessage>,
        text: String?,
        imageBase64: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig()
            val body = buildJsonObject {
                put("model", config.modelName)
                put("stream", false)
                putJsonArray("messages") {
                    addSystemPrompt()
                    addHistory(history)
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                if (!text.isNullOrBlank()) {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        }
                                    )
                                }
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:image/jpeg;base64,$imageBase64")
                                        }
                                    }
                                )
                            })
                        }
                    )
                }
            }.toString().toRequestBody(mediaType)

            extractContent(executeApiCall(config, body))
        }
    }

    // ──────────────────────────────────────────
    // 配置 & HTTP
    // ──────────────────────────────────────────

    private suspend fun requireConfig(): AiModelConfig {
        val config = aiModelRepository.getPrimaryModelForPurpose(AiModelConfig.PURPOSE_TEXT_SEARCH)
            ?: error("请先在\"我的 → AI 模型管理\"中配置文字搜索模型")
        require(config.apiKey.isNotBlank()) {
            "请先在\"我的 → AI 模型管理\"中填写 DeepSeek 的 API Key"
        }
        require(config.modelName.isNotBlank()) {
            "请先在\"我的 → AI 模型管理\"中填写模型名称"
        }
        return config
    }

    /**
     * 执行 HTTP 请求，返回原始响应 JSON 字符串。
     */
    private fun executeApiCall(config: AiModelConfig, body: okhttp3.RequestBody): String {
        val url = config.endpoint.chatCompletionsUrl()
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                val errorBody = it.body?.string().orEmpty()
                val serverMessage = try {
                    json.parseToJsonElement(errorBody).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) { null }

                val errorMsg = when (it.code) {
                    401 -> "API Key 无效，请在\"我的 → AI 模型管理\"中检查密钥是否正确"
                    402 -> "账户余额不足或计费异常，请检查 DeepSeek 控制台余额与 API 计费状态"
                    403 -> "API Key 没有访问权限，请检查账户状态或联系 API 提供商"
                    400, 422 -> serverMessage ?: "请求格式不符合 DeepSeek 接口要求"
                    429 -> "请求过于频繁，请稍后重试"
                    500, 502, 503, 504 -> "AI 服务暂时不可用，请稍后重试"
                    else -> serverMessage ?: "HTTP ${it.code}"
                }
                error("AI 请求失败：$errorMsg")
            }
            return it.body?.string().orEmpty()
        }
    }

    private fun String.chatCompletionsUrl(): String {
        val normalized = trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    // ──────────────────────────────────────────
    // 响应解析
    // ──────────────────────────────────────────

    /** 从 API 响应中提取文本内容，失败时抛出异常 */
    private fun extractContent(responseBody: String): String {
        return extractContentOrNull(responseBody)
            ?: error("AI 没有返回可用内容，请稍后重试")
    }

    private fun extractContentOrNull(responseBody: String): String? {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("AI 响应格式异常：无 choices")
        return choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    /** 从 API 响应中提取 tool_calls 列表 */
    private fun extractToolCalls(responseBody: String): List<ToolCall> {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        val message = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject ?: return emptyList()
        val toolCallsJson = message["tool_calls"]?.jsonArray ?: return emptyList()

        return toolCallsJson.mapNotNull { tc ->
            val fn = tc.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
            ToolCall(
                id = tc.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = fn["name"]?.jsonPrimitive?.contentOrNull ?: "",
                arguments = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            )
        }
    }

    /** 提取 message 对象（用于工具调用后回传） */
    private fun extractMessageJson(responseBody: String): JsonObject {
        val obj = json.parseToJsonElement(responseBody).jsonObject
        return obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?: error("AI 响应格式异常：无法提取 message")
    }

    // ──────────────────────────────────────────
    // 请求构造
    // ──────────────────────────────────────────

    private fun buildRequestJson(
        config: AiModelConfig,
        history: List<ChatMessage>,
        newMessage: String,
        includeTools: Boolean
    ): okhttp3.RequestBody {
        return buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                addSystemPrompt()
                addHistory(history)
                addUserMessage(newMessage)
            }
            if (includeTools) {
                put("tools", TOOLS_JSON)
            }
        }.toString().toRequestBody(mediaType)
    }

    /**
     * 构造工具调用结果回传的请求体：
     * [system, ...history, user, assistant(tool_calls), tool(result1), tool(result2), ...]
     */
    private fun buildToolResultRequest(
        config: AiModelConfig,
        history: List<ChatMessage>,
        newMessage: String,
        assistantMessage: JsonObject,
        toolResults: List<Pair<ToolCall, String>>
    ): okhttp3.RequestBody {
        return buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                addSystemPrompt()
                addHistory(history)
                addUserMessage(newMessage)
                // AI 返回的 assistant 消息（含 tool_calls）
                add(assistantMessage)
                // 每条工具结果
                toolResults.forEach { (tc, resultText) ->
                    add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", resultText)
                        }
                    )
                }
            }
            put("tools", TOOLS_JSON)
        }.toString().toRequestBody(mediaType)
    }

    // ──────────────────────────────────────────
    // 工具执行
    // ──────────────────────────────────────────

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private suspend fun executeToolCall(toolCall: ToolCall): String {
        return try {
            val args = json.parseToJsonElement(toolCall.arguments).jsonObject
            when (toolCall.name) {
                "search_items" -> {
                    val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val items = inventoryTool.searchItemsSnapshot(keyword)
                    formatSearchResults(items, keyword)
                }
                "get_items_by_location" -> {
                    val location = args["location"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val items = inventoryTool.getItemsByLocationSnapshot(location)
                    formatLocationResults(items, location)
                }
                "get_expiring_items" -> {
                    val days = args["days"]?.jsonPrimitive?.intOrNull ?: 7
                    val items = inventoryTool.getExpiringItemsSnapshot(days)
                    formatExpiryResults(items, days)
                }
                "get_inventory_summary" -> {
                    val summary = inventoryTool.getInventorySummary()
                    formatSummary(summary)
                }
                "get_used_up_items" -> {
                    val items = inventoryTool.getUsedUpItemsSnapshot()
                    formatUsedUpResults(items)
                }
                else -> "未知工具：${toolCall.name}"
            }
        } catch (e: Exception) {
            "工具调用失败（${toolCall.name}）：${e.message}"
        }
    }

    // ──────────────────────────────────────────
    // 结果格式化
    // ──────────────────────────────────────────

    private fun formatSearchResults(items: List<ItemDetail>, keyword: String): String {
        if (items.isEmpty()) {
            return "没有找到与\"$keyword\"相关的物品。"
        }
        return buildString {
            appendLine("找到 ${items.size} 件与\"$keyword\"相关的物品：")
            items.forEachIndexed { i, item ->
                val loc = item.locationName ?: "未设置位置"
                val cat = item.categoryName ?: "未分类"
                val qty = "${item.item.quantity}${item.item.unit}"
                val expire = item.item.expireTime?.let { t ->
                    val remainingDays = (t - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)
                    if (remainingDays in 0..30) " ⚠️${remainingDays}天后过期" else ""
                } ?: ""
                appendLine("${i + 1}. ${item.item.name} | 位置：$loc | 分类：$cat | 数量：$qty$expire")
                if (item.item.note.isNotBlank()) {
                    appendLine("   备注：${item.item.note}")
                }
            }
        }.trimEnd()
    }

    private fun formatLocationResults(items: List<ItemDetail>, location: String): String {
        if (items.isEmpty()) {
            return "在\"$location\"没有找到任何物品。"
        }
        return buildString {
            appendLine("在\"$location\"找到 ${items.size} 件物品：")
            items.forEachIndexed { i, item ->
                val loc = item.locationName ?: "未设置位置"
                val qty = "${item.item.quantity}${item.item.unit}"
                appendLine("${i + 1}. ${item.item.name}（$qty）— $loc")
            }
        }.trimEnd()
    }

    private fun formatExpiryResults(items: List<ItemDetail>, days: Int): String {
        if (items.isEmpty()) {
            return "太好了！${days}天内没有即将过期的物品。"
        }
        return buildString {
            appendLine("${days}天内即将过期 ${items.size} 件物品：")
            items.forEachIndexed { i, item ->
                val loc = item.locationName ?: "未设置位置"
                val remainingDays = item.item.expireTime?.let { t ->
                    (t - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)
                } ?: 0
                appendLine("${i + 1}. ${item.item.name} | $loc | 还剩${remainingDays}天")
            }
        }.trimEnd()
    }

    private fun formatUsedUpResults(items: List<ItemDetail>): String {
        if (items.isEmpty()) {
            return "目前没有标记为已用完的物品。"
        }
        return buildString {
            appendLine("家里已用完的物品有 ${items.size} 件：")
            items.forEachIndexed { i, item ->
                val loc = item.locationName ?: "未设置位置"
                val cat = item.categoryName ?: "未分类"
                appendLine("${i + 1}. ${item.item.name} | 位置：$loc | 分类：$cat")
            }
        }.trimEnd()
    }

    private fun buildToolFallbackAnswer(
        userMessage: String,
        toolResults: List<Pair<ToolCall, String>>
    ): String? {
        val usableResults = toolResults.map { it.second.trim() }.filter { it.isNotBlank() }
        if (usableResults.isNotEmpty()) {
            return usableResults.joinToString("\n\n")
        }
        return null
    }

    private fun formatSummary(summary: InventoryAgentTool.InventorySummary): String {
        return buildString {
            appendLine("库存概况：")
            appendLine("- 在库物品：${summary.activeItemCount} 件")
            appendLine("- 已用完：${summary.usedUpCount} 件")
            appendLine("- 7天内过期：${summary.expiringSoonCount} 件")
            if (summary.totalValue > 0) {
                appendLine("- 总价值：¥${"%.2f".format(summary.totalValue)}")
            }
            if (summary.categoryBreakdown.isNotEmpty()) {
                val catStr = summary.categoryBreakdown.joinToString("、") { "${it.categoryName} ${it.count}件" }
                appendLine("- 分类分布：$catStr")
            }
            if (summary.locationBreakdown.isNotEmpty()) {
                val locStr = summary.locationBreakdown.take(5).joinToString("、") { "${it.locationName} ${it.count}件" }
                appendLine("- 位置分布（Top5）：$locStr")
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────
    // Messages 构建辅助
    // ──────────────────────────────────────────

    private fun ChatRole.toApiRole(): String = when (this) {
        ChatRole.USER -> "user"
        ChatRole.ASSISTANT -> "assistant"
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addSystemPrompt() {
        add(
            buildJsonObject {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            }
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addHistory(history: List<ChatMessage>) {
        history
            .filter { it.status == ChatMessageStatus.NORMAL }
            .takeLast(HISTORY_MAX_MESSAGES)
            .forEach { msg ->
                add(
                    buildJsonObject {
                        put("role", msg.role.toApiRole())
                        put("content", msg.content)
                    }
                )
            }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addUserMessage(message: String) {
        add(
            buildJsonObject {
                put("role", "user")
                put("content", message)
            }
        )
    }

    // ──────────────────────────────────────────
    // 常量
    // ──────────────────────────────────────────

    companion object {
        /** 工具调用最大轮数，防止无限循环 */
        private const val MAX_TOOL_ROUNDS = 3

        /** 发送给 AI 的历史消息上限，防止超出 token 限制 */
        private const val HISTORY_MAX_MESSAGES = 30

        private val SYSTEM_PROMPT = """
你是"小东西"，一个温暖、细心、简洁的家庭物品管理助手。

你可以直接查询用户的库存数据库来回答问题。使用提供的工具函数来获取真实数据，然后基于实际数据给出建议。

你的性格：
- 语气亲切自然，像家人一样，用"你"称呼用户
- 回答简洁直接，不啰嗦
- 每次回复控制在 2~5 句话以内，除非用户明确要求详细说明
- 说中文

重要规则：
- 当用户询问物品在哪、有没有某物、什么东西快过期、库存情况等问题时，
  务必先调用工具查询真实数据，再基于数据回答。
- 永远基于工具返回的数据说话，不要编造。
- 如果工具返回空结果，如实告诉用户没找到，可以建议用户先录入物品。
- search_items 可以搜索名称、分类、位置、备注。
        """.trimIndent()

        /** OpenAI/DeepSeek function calling 工具定义 */
        private val TOOLS_JSON = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "search_items")
                        put("description", "搜索物品，按关键词匹配名称、分类、位置、备注。用于回答\"我的XX在哪\"\"有没有XX\"等问题。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("keyword") {
                                    put("type", "string")
                                    put("description", "搜索关键词，如物品名称、品牌、类型等")
                                }
                            }
                            putJsonArray("required") { add(jsonPrimitive("keyword")) }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "get_used_up_items")
                        put("description", "列出已经标记为用完的物品。用于回答“家里有哪些东西用完了”“哪些已经用完”“已用完清单”等问题。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "get_items_by_location")
                        put("description", "查询某个位置下的所有物品。支持子位置递归，如\"卧室\"会包含床头柜、衣柜等子位置。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("location") {
                                    put("type", "string")
                                    put("description", "位置名称，如\"卧室\"\"厨房\"\"冰箱\"")
                                }
                            }
                            putJsonArray("required") { add(jsonPrimitive("location")) }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "get_expiring_items")
                        put("description", "查询N天内即将过期的物品列表。默认7天。用于回答\"什么快过期了\"\"冰箱里有什么快过期\"等问题。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("days") {
                                    put("type", "integer")
                                    put("description", "天数范围，如7表示7天内过期，30表示30天内过期。默认7。")
                                }
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "get_inventory_summary")
                        put("description", "获取库存概况：物品总数、已用完数量、即将过期数量、分类/位置分布。用于回答\"库存概况\"\"我有多少东西\"等问题。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                // 无参数
                            }
                        }
                    }
                }
            )
        }

        /** JSON 原始类型辅助 */
        private fun jsonPrimitive(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    }
}
