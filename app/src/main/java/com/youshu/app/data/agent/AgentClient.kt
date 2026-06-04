package com.youshu.app.data.agent

import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.local.entity.Item
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
    private val inventoryTool: InventoryAgentTool,
    private val weatherTool: WeatherAgentTool
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
            val recentItemContext = inventoryTool.getRecentItemContext()
            val locationTreeContext = inventoryTool.getLocationTreeContext()
            val toolNudge = buildToolNudge(newMessage)
            val requestLocationTree = locationTreeContext.takeUnless { toolNudge?.hideLocationTree == true }

            // 发送消息，带工具调用循环（最多 MAX_TOOL_ROUNDS 轮）
            var currentResponse = executeApiCall(
                config,
                buildRequestJson(
                    config = config,
                    history = history,
                    newMessage = newMessage,
                    recentItemContext = recentItemContext,
                    locationTreeContext = requestLocationTree,
                    toolNudge = toolNudge?.text,
                    allowedToolNames = toolNudge?.allowedToolNames,
                    includeTools = true
                )
            )
            var lastToolResults: List<Pair<ToolCall, String>> = emptyList()
            var retriedMissingTool = false

            for (round in 1..MAX_TOOL_ROUNDS) {
                val toolCalls = extractToolCalls(currentResponse)
                if (toolCalls.isEmpty()) {
                    if (lastToolResults.isEmpty() && !retriedMissingTool && toolNudge != null) {
                        retriedMissingTool = true
                        currentResponse = executeApiCall(
                            config,
                            buildRequestJson(
                                config = config,
                                history = history,
                                newMessage = newMessage,
                                recentItemContext = recentItemContext,
                                locationTreeContext = requestLocationTree,
                                toolNudge = "${toolNudge.text}\n注意：上一轮没有检测到工具调用。用户是在要求查询或修改真实数据，不能只说“我帮你查看/我来处理”，必须先调用对应工具，拿到工具结果后再回复。",
                                allowedToolNames = toolNudge.allowedToolNames,
                                includeTools = true
                            )
                        )
                        continue
                    }
                    break // AI 直接返回了文本，结束
                }

                // 执行所有工具调用
                val toolResults = toolCalls.map { tc -> tc to executeToolCall(tc, newMessage) }
                lastToolResults = toolResults
                if (toolResults.any { (tc, _) -> isMutationTool(tc.name) }) {
                    return@runCatching buildToolFallbackAnswer(newMessage, toolResults)
                        ?: error("修改工具已经执行，但没有返回可展示的结果。")
                }

                // 将工具结果送回 AI
                currentResponse = executeApiCall(
                    config,
                    buildToolResultRequest(
                        config = config,
                        history = history,
                        newMessage = newMessage,
                        assistantMessage = extractMessageJson(currentResponse),
                        toolResults = toolResults,
                        recentItemContext = inventoryTool.getRecentItemContext(),
                        locationTreeContext = requestLocationTree,
                        toolNudge = buildNextToolNudge(toolNudge, toolResults),
                        allowedToolNames = nextAllowedToolNames(toolNudge, toolResults),
                        includeTools = shouldIncludeToolsAfterResult(toolNudge, toolResults)
                    )
                )
            }

            val content = extractContentOrNull(currentResponse)
            val toolAnswer = buildToolFallbackAnswer(newMessage, lastToolResults)
            val weatherFallback = buildWeatherFallbackAnswer(lastToolResults)
            val safeToolAnswer = toolAnswer.takeUnless { isWeatherToolResult(lastToolResults) }
            safeToolAnswer
                ?.takeIf { content == null || shouldPreferToolResult(content, lastToolResults) }
                ?: content?.takeUnless {
                    (toolNudge?.requiresToolCall == true && lastToolResults.isEmpty()) ||
                        (isWeatherToolResult(lastToolResults) && isRawWeatherToolAnswer(it))
                }
                ?: weatherFallback
                ?: safeToolAnswer
                ?: "这次没有真正调用到对应工具，数据没有被改动。你可以换个更明确的说法再试一次，比如“删除宿舍这个场景”。"
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
            ?: error("请先在\"我的 → API-Key 管理系统\"中配置文字搜索模型")
        require(config.apiKey.isNotBlank()) {
            "请先在\"我的 → API-Key 管理系统\"中填写 DeepSeek 的 API Key"
        }
        require(config.modelName.isNotBlank()) {
            "请先在\"我的 → API-Key 管理系统\"中填写模型名称"
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
                    401 -> "API Key 无效，请在\"我的 → API-Key 管理系统\"中检查密钥是否正确"
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
        recentItemContext: String?,
        locationTreeContext: String?,
        toolNudge: String?,
        allowedToolNames: Set<String>? = null,
        includeTools: Boolean
    ): okhttp3.RequestBody {
        return buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                addSystemPrompt()
                addRecentItemContext(recentItemContext)
                addLocationTreeContext(locationTreeContext)
                addToolNudge(toolNudge)
                addHistory(history)
                addUserMessage(newMessage)
            }
            if (includeTools) {
                put("tools", selectTools(allowedToolNames))
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
        toolResults: List<Pair<ToolCall, String>>,
        recentItemContext: String?,
        locationTreeContext: String?,
        toolNudge: String? = null,
        allowedToolNames: Set<String>? = null,
        includeTools: Boolean = true
    ): okhttp3.RequestBody {
        return buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                addSystemPrompt()
                addRecentItemContext(recentItemContext)
                addLocationTreeContext(locationTreeContext)
                addToolNudge(toolNudge)
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
            if (includeTools) {
                put("tools", selectTools(allowedToolNames))
            }
        }.toString().toRequestBody(mediaType)
    }

    private fun selectTools(allowedToolNames: Set<String>?) = buildJsonArray {
        TOOLS_JSON.forEach { tool ->
            val name = tool.jsonObject["function"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
            if (allowedToolNames == null || name in allowedToolNames) {
                add(tool)
            }
        }
    }

    private fun nextAllowedToolNames(
        toolNudge: ToolNudge?,
        toolResults: List<Pair<ToolCall, String>>
    ): Set<String>? {
        if (toolNudge?.flow != ToolFlow.Weather) return toolNudge?.allowedToolNames
        val names = toolResults.map { it.first.name }.toSet()
        return when {
            "get_weather_context" in names -> setOf("find_weather_items")
            "find_weather_items" in names -> emptySet()
            else -> toolNudge.allowedToolNames
        }
    }

    private fun buildNextToolNudge(
        toolNudge: ToolNudge?,
        toolResults: List<Pair<ToolCall, String>>
    ): String? {
        if (toolNudge?.flow != ToolFlow.Weather) return null
        val names = toolResults.map { it.first.name }.toSet()
        return when {
            "get_weather_context" in names -> {
                "已经拿到真实天气。现在必须调用 find_weather_items 查家里是否有这次建议可能用到的物品，例如雨伞、外套、防晒霜、帽子、水杯等；不要直接回答。"
            }
            "find_weather_items" in names -> {
                "现在不要再调用工具。请基于真实天气和物品候选，用自然中文回答用户：说明明天天气、穿衣/饮食/带伞/防晒建议；如果候选里有相关物品就告诉用户放在哪里，没有就自然说目前没在对应地点找到。不要输出工具原文、数据源说明或“请结合上述”。"
            }
            else -> null
        }
    }

    private fun shouldIncludeToolsAfterResult(
        toolNudge: ToolNudge?,
        toolResults: List<Pair<ToolCall, String>>
    ): Boolean {
        val nextAllowed = nextAllowedToolNames(toolNudge, toolResults)
        return nextAllowed != emptySet<String>()
    }

    // ──────────────────────────────────────────
    // 工具执行
    // ──────────────────────────────────────────

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class ToolNudge(
        val text: String,
        val allowedToolNames: Set<String>? = null,
        val hideLocationTree: Boolean = false,
        val requiresToolCall: Boolean = false,
        val flow: ToolFlow = ToolFlow.Default
    )

    private enum class ToolFlow {
        Default,
        Weather
    }

    private fun buildToolNudge(userMessage: String): ToolNudge? {
        val text = userMessage.trim()
        if (text.isBlank()) return null
        val hasMutationWord = listOf(
            "添加", "新增", "加上", "创建", "建立", "删除", "删掉", "移除",
            "标记", "改成", "设置", "设为", "评价", "评分", "写评价",
            "确认", "确定", "同意", "删吧", "都删了", "继续删", "可以删"
        ).any { text.contains(it) }
        val hasQueryWord = listOf(
            "查", "查看", "看看", "找", "在哪", "哪里", "有没有", "哪些", "什么",
            "清单", "列表", "库存", "过期", "天气", "穿", "带伞", "防晒", "冷热"
        ).any { text.contains(it) }
        if (!hasMutationWord && !hasQueryWord) return null

        var allowedToolNames: Set<String>? = null
        var hideLocationTree = false
        var requiresToolCall = hasMutationWord
        val targetTool = when {
            isSceneAddAndLocationDeleteRequest(text) -> {
                allowedToolNames = setOf("add_scene", "preview_delete_location_tree")
                requiresToolCall = true
                "add_scene 和 preview_delete_location_tree。用户同时要求添加一个场景/大位置并删除另一个位置/场景时，必须在同一轮调用两个工具：先调用 add_scene 添加新场景，再调用 preview_delete_location_tree 预览要删除的位置并让用户确认。"
            }
            shouldUseSceneTool(text) &&
                listOf("添加", "新增", "加上", "创建", "建立").any { text.contains(it) } -> {
                allowedToolNames = setOf("add_scene")
                hideLocationTree = true
                requiresToolCall = true
                "add_scene。场景名放到 name；用户说自动推荐位置时 child_locations 传空数组；用户列出子位置时传入 child_locations。"
            }
            listOf("位置", "存放", "地点", "地方", "区域", "房间", "下面", "下一级").any { text.contains(it) } &&
                listOf("添加", "新增", "加上", "创建", "建立").any { text.contains(it) } -> {
                allowedToolNames = setOf("add_location")
                hideLocationTree = true
                requiresToolCall = true
                "add_location。新增最外层位置时 parent_location 传空字符串；新增子位置时 parent_location 传父位置或完整路径。"
            }
            listOf("确认", "确定", "同意", "删吧", "都删了", "继续删", "可以删").any { text.contains(it) } -> {
                allowedToolNames = setOf("delete_location_tree")
                requiresToolCall = true
                "delete_location_tree。用户是在确认上一轮的位置/场景删除预览时调用；name 可以传空字符串，工具会使用最近一次待确认删除的位置。"
            }
            listOf("位置", "存放", "地点", "地方", "区域", "房间", "场景", "大位置").any { text.contains(it) } &&
                listOf("删除", "删掉", "移除").any { text.contains(it) } -> {
                allowedToolNames = setOf("preview_delete_location_tree")
                requiresToolCall = true
                "preview_delete_location_tree。场景本质是最外层位置；删除位置/场景前先调用本工具预览子位置和物品，并请用户确认。不要直接调用删除工具。"
            }
            listOf("分类", "类别", "种类").any { text.contains(it) } &&
                listOf("添加", "新增", "加上", "创建", "建立").any { text.contains(it) } -> {
                allowedToolNames = setOf("add_category")
                requiresToolCall = true
                "add_category。name 传分类名，icon 用户没说时可省略或传空字符串。"
            }
            listOf("分类", "类别", "种类").any { text.contains(it) } &&
                listOf("删除", "删掉", "移除").any { text.contains(it) } -> {
                allowedToolNames = setOf("delete_category")
                requiresToolCall = true
                "delete_category。name 传分类名。"
            }
            listOf("评价", "评分", "写评价").any { text.contains(it) } -> {
                allowedToolNames = setOf("write_item_review")
                requiresToolCall = true
                "write_item_review。keyword 传物品名；如果用户指代刚刚那个物品，keyword 可以留空。"
            }
            listOf("用完", "没用完", "未用完", "丢弃", "废弃").any { text.contains(it) } &&
                listOf("标记", "改成", "设置", "设为").any { text.contains(it) } -> {
                allowedToolNames = setOf("update_item_status")
                requiresToolCall = true
                "update_item_status。keyword 传物品名；status 按用户意思传 used_up、in_use 或 discarded。"
            }
            listOf("删除", "删掉", "移除").any { text.contains(it) } -> {
                allowedToolNames = setOf("delete_item", "preview_delete_location_tree")
                requiresToolCall = true
                "delete_item 或 preview_delete_location_tree。若用户要删的是物品，调用 delete_item；若用户要删的是位置/场景/宿舍/办公室/公司这类存放地点，先调用 preview_delete_location_tree 预览并让用户确认。"
            }
            listOf("天气", "穿", "带伞", "防晒", "冷热").any { text.contains(it) } -> {
                allowedToolNames = setOf("get_weather_context")
                requiresToolCall = true
                "get_weather_context。天气问题必须先调用本工具查询真实天气；拿到天气后，再调用 find_weather_items 查询建议物品位置，最后用自然中文回答。"
            }
            listOf("过期", "快过期", "临期").any { text.contains(it) } -> {
                allowedToolNames = setOf("get_expiring_items")
                requiresToolCall = true
                "get_expiring_items。days 按用户说的时间范围传，没说时传 7。"
            }
            listOf("用完", "已用完").any { text.contains(it) } &&
                listOf("查", "查看", "哪些", "清单", "列表", "有什么", "有没有").any { text.contains(it) } -> {
                allowedToolNames = setOf("get_used_up_items")
                requiresToolCall = true
                "get_used_up_items。"
            }
            listOf("在哪", "哪里", "有没有", "找", "查", "查看", "库存", "什么").any { text.contains(it) } -> {
                allowedToolNames = setOf("search_items", "find_related_items", "get_items_by_location")
                requiresToolCall = true
                "search_items 或 find_related_items。用户说的是泛称/同义词/品类词时优先 find_related_items；用户问某个位置下有什么时调用 get_items_by_location。"
            }
            else -> return null
        }

        return ToolNudge(
            text = "这条用户消息需要查询或修改真实数据。你必须调用工具 $targetTool 工具返回后再回复；如果对象不清楚，先调用工具让工具判断，或自然追问。不要只说“我帮你查看/我来处理/稍等”。工具结果比你看到的上下文更重要，尤其是添加场景/位置后，必须按工具结果说“已添加/已补全/已存在”。",
            allowedToolNames = allowedToolNames,
            hideLocationTree = hideLocationTree,
            requiresToolCall = requiresToolCall,
            flow = if (allowedToolNames == setOf("get_weather_context")) ToolFlow.Weather else ToolFlow.Default
        )
    }

    private fun shouldUseSceneTool(text: String): Boolean {
        if (isStandaloneSceneRequest(text)) return true
        if (listOf("场景", "大位置").any { text.contains(it) }) return true
        if (!listOf("位置", "存放", "地点", "地方", "区域").any { text.contains(it) }) return false
        return listOf("下面的位置", "子位置", "下级位置", "推荐", "生成", "一套", "一组").any { text.contains(it) }
    }

    private fun isSceneAddAndLocationDeleteRequest(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        val hasAdd = listOf("添加", "新增", "创建", "建立", "加上").any { compact.contains(it) }
        val hasDelete = listOf("删除", "删掉", "移除").any { compact.contains(it) }
        if (!hasAdd || !hasDelete) return false
        val knownSceneNames = setOf(
            "办公室", "办公区", "宿舍", "公司", "学校", "教室", "实验室",
            "出租屋", "租房", "车里", "车上", "车内", "车", "厨房"
        )
        val hasKnownScene = knownSceneNames.any { compact.contains(it) }
        return hasKnownScene || listOf("场景", "大位置", "位置", "地点", "地方").any { compact.contains(it) }
    }

    private fun isStandaloneSceneRequest(text: String): Boolean {
        val candidate = text
            .replace(Regex("\\s+"), "")
            .replace(Regex("^(帮我|请|麻烦你|给我|帮忙)?(添加|新增|创建|建立|加上)(一个|一处|新的|新)?"), "")
            .replace(Regex("(这个)?(场景|大位置|地点|地方|区域)$"), "")
            .trim()
        return candidate in setOf(
            "办公室",
            "办公区",
            "宿舍",
            "公司",
            "学校",
            "教室",
            "实验室",
            "出租屋",
            "租房",
            "车里",
            "车上",
            "车内",
            "车",
            "厨房"
        )
    }

    private suspend fun executeToolCall(toolCall: ToolCall, userMessage: String): String {
        return try {
            val args = json.parseToJsonElement(toolCall.arguments).jsonObject
            when (toolCall.name) {
                "search_items" -> {
                    val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val items = inventoryTool.searchItemsSnapshot(keyword)
                    formatSearchResults(items, keyword)
                }
                "find_related_items" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val statusScope = args["status_scope"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val items = inventoryTool.getSemanticSearchCandidatesSnapshot(query, statusScope)
                    formatRelatedCandidatePool(items, query, statusScope)
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
                "get_weather_context" -> {
                    val city = args["city"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val intent = args["intent"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    weatherTool.getWeatherContext(city, intent)
                }
                "find_weather_items" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val placeScope = args["place_scope"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val items = inventoryTool.getWeatherItemCandidatesSnapshot(query, placeScope)
                    formatWeatherItemCandidatePool(items, query, placeScope)
                }
                "update_item_status" -> {
                    inventoryTool.updateItemStatus(
                        keyword = args["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        status = args["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        rating = parseRatingArgument(args, userMessage),
                        reviewNote = args["review_note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "write_item_review" -> {
                    inventoryTool.writeItemReview(
                        keyword = args["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        rating = parseRatingArgument(args, userMessage),
                        reviewNote = args["review_note"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "delete_item" -> {
                    inventoryTool.deleteItem(
                        keyword = args["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "add_location" -> {
                    inventoryTool.addLocation(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        parentLocation = args["parent_location"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "add_scene" -> {
                    inventoryTool.addScene(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        childLocations = args["child_locations"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            .orEmpty()
                    )
                }
                "delete_location" -> {
                    inventoryTool.deleteLocation(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        parentLocation = args["parent_location"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "preview_delete_location_tree" -> {
                    inventoryTool.previewDeleteLocationTree(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        parentLocation = args["parent_location"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "delete_location_tree" -> {
                    inventoryTool.deleteLocationTree(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        parentLocation = args["parent_location"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "add_category" -> {
                    inventoryTool.addCategory(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        icon = args["icon"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }
                "delete_category" -> {
                    inventoryTool.deleteCategory(
                        name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
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

    private fun formatRelatedCandidatePool(
        items: List<ItemDetail>,
        query: String,
        statusScope: String
    ): String {
        if (items.isEmpty()) {
            return "按“${statusScopeLabel(statusScope)}”范围没有可供语义筛选的物品。"
        }
        return buildString {
            appendLine("语义候选池，不是最终结果。用户查询：\"$query\"；状态范围：${statusScopeLabel(statusScope)}。")
            appendLine("请从下面候选物品中，按常识和语义关系挑出真正相关的物品，例如“矿泉水”可以包含“农夫山泉/怡宝/百岁山/瓶装饮用水”。")
            appendLine("最终回复只列出你判断相关的物品；如果有多件，全部列出并让用户选择；如果没有任何相关项，再说没找到。候选共 ${items.size} 件：")
            items.forEachIndexed { i, item ->
                appendLine("${i + 1}. ${formatItemForAgent(item)}")
            }
        }.trimEnd()
    }

    private fun formatWeatherItemCandidatePool(
        items: List<ItemDetail>,
        query: String,
        placeScope: String
    ): String {
        val place = placeScope.trim().ifBlank { "家" }
        if (items.isEmpty()) {
            return "在“$place”没有可供天气建议参考的在库物品。"
        }
        return buildString {
            appendLine("天气建议可用物品候选池，不是最终结果。地点范围：$place；天气/出行意图：\"$query\"。")
            appendLine("请结合真实天气，从候选池里挑出用户这次可能需要的物品，并告诉用户它们放在哪里。")
            appendLine("如果某类建议物品在候选池里没有找到，可以自然说明“没在${place}找到”。候选共 ${items.size} 件：")
            items.forEachIndexed { i, item ->
                appendLine("${i + 1}. ${formatItemForAgent(item)}")
            }
        }.trimEnd()
    }

    private fun formatItemForAgent(item: ItemDetail): String {
        val loc = item.locationName ?: "未设置位置"
        val cat = item.categoryName ?: "未分类"
        val qty = "${item.item.quantity}${item.item.unit}"
        val note = item.item.note.trim().takeIf { it.isNotBlank() }?.let { " | 备注：$it" }.orEmpty()
        val review = item.item.reviewNote.trim().takeIf { it.isNotBlank() }?.let { " | 评价：$it" }.orEmpty()
        val expire = formatExpireForAgent(item)
        return "${item.item.name} | 状态：${statusLabel(item.item.status)} | 位置：$loc | 分类：$cat | 数量：$qty$expire$note$review"
    }

    private fun formatExpireForAgent(item: ItemDetail): String {
        val expireTime = item.item.expireTime ?: return ""
        val days = (expireTime - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)
        return " | 过期：${days}天后"
    }

    private fun statusLabel(status: Int): String {
        return when (status) {
            Item.STATUS_IN_USE -> "未用完/在库"
            Item.STATUS_USED_UP -> "已用完"
            Item.STATUS_DISCARDED -> "已丢弃"
            else -> "未知"
        }
    }

    private fun statusScopeLabel(scope: String): String {
        return when (scope.trim().lowercase()) {
            "used_up", "used", "已用完", "用完" -> "已用完"
            "discarded", "丢弃", "废弃" -> "已丢弃"
            "all", "全部", "所有" -> "全部状态"
            else -> "未用完/在库"
        }
    }

    private fun parseRatingArgument(args: JsonObject, userMessage: String): Int? {
        val explicit = args["rating"]?.jsonPrimitive
        return explicit?.intOrNull?.coerceIn(1, 5)
            ?: explicit?.contentOrNull?.let { parseRatingText(it) }
            ?: args["review_note"]?.jsonPrimitive?.contentOrNull?.let { parseRatingText(it) }
            ?: parseRatingText(userMessage)
    }

    private fun parseRatingText(text: String): Int? {
        val normalized = text.trim()
        if (normalized.isBlank()) return null
        val starCount = normalized.count { it == '⭐' || it == '★' }
        if (starCount in 1..5) return starCount
        if (normalized.matches(Regex("[1-5]"))) {
            return normalized.toInt()
        }
        Regex("([1-5])\\s*(星|分|颗|顆)").find(normalized)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it.coerceIn(1, 5) }
        val chineseRatings = listOf(
            "一" to 1,
            "二" to 2,
            "两" to 2,
            "三" to 3,
            "四" to 4,
            "五" to 5
        )
        chineseRatings.firstOrNull { (word, _) ->
            normalized.contains("${word}星") ||
                normalized.contains("${word}颗星") ||
                normalized.contains("${word}顆星") ||
                normalized.contains("${word}分")
        }?.let { return it.second }
        if (normalized.contains("满分") || normalized.contains("满星") || normalized.contains("五星好评")) {
            return 5
        }
        return null
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
        val usableResults = toolResults.mapNotNull { (toolCall, result) ->
            formatToolResultForUser(toolCall, result.trim()).takeIf { it.isNotBlank() }
        }
        if (usableResults.isNotEmpty()) {
            return usableResults.joinToString("\n\n")
        }
        return null
    }

    private fun formatToolResultForUser(toolCall: ToolCall, result: String): String {
        if (result.isBlank()) return result
        if (!isMutationTool(toolCall.name)) return result
        if (listOf("已经存在", "已经有").any { result.contains(it) }) return result
        return if (isSuccessfulMutationResult(result)) {
            "搞定，$result"
        } else {
            result
        }
    }

    private fun shouldPreferToolResult(
        content: String,
        toolResults: List<Pair<ToolCall, String>>
    ): Boolean {
        if (toolResults.isEmpty()) return false
        if (isVagueToolAnswer(content, toolResults)) return true

        val normalized = content.trim()
        val hasMutationResult = toolResults.any { (toolCall, result) ->
            isMutationTool(toolCall.name) && result.isNotBlank()
        }
        if (!hasMutationResult) return false

        val contentStillWaiting = listOf(
            "我帮你查看",
            "我来查看",
            "我帮你查",
            "我来查",
            "我先看一下",
            "我先查一下",
            "我来看看",
            "稍等"
        ).any { normalized.contains(it) }
        if (contentStillWaiting) return true

        val toolSaysAddedOrUpdated = toolResults.any { (_, result) ->
            isSuccessfulMutationResult(result) && listOf("已添加", "已在", "已为", "已标记", "已保存", "已删除").any {
                result.contains(it)
            }
        }
        val contentSaysAlreadyExisted = listOf(
            "已经添加过",
            "之前已经添加",
            "已经存在",
            "之前已经有",
            "原本就有",
            "早就有"
        ).any { normalized.contains(it) }
        return toolSaysAddedOrUpdated && contentSaysAlreadyExisted
    }

    private fun isWeatherToolResult(toolResults: List<Pair<ToolCall, String>>): Boolean {
        return toolResults.any { (toolCall, _) ->
            toolCall.name == "get_weather_context" || toolCall.name == "find_weather_items"
        }
    }

    private fun isRawWeatherToolAnswer(content: String): Boolean {
        return listOf(
            "天气数据源：",
            "天气查询地点：",
            "用户意图：",
            "请结合上述真实天气",
            "天气建议可用物品候选池",
            "不是最终结果",
            "请结合真实天气"
        ).any { content.contains(it) }
    }

    private fun buildWeatherFallbackAnswer(
        toolResults: List<Pair<ToolCall, String>>
    ): String? {
        if (!isWeatherToolResult(toolResults)) return null
        val weatherText = toolResults
            .lastOrNull { it.first.name == "get_weather_context" }
            ?.second
            .orEmpty()
        val itemText = toolResults
            .lastOrNull { it.first.name == "find_weather_items" }
            ?.second
            .orEmpty()
        val tomorrow = weatherText.lineSequence()
            .firstOrNull { it.startsWith("明天预报：") }
            ?.removePrefix("明天预报：")
            .orEmpty()
        if (tomorrow.isBlank()) return null

        val needsUmbrella = tomorrow.contains("雨")
        val hot = Regex("""(\d+)℃""")
            .findAll(tomorrow)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .any { it >= 30 }
        val itemLine = when {
            itemText.contains("没有可供天气建议参考") -> "我看了下家里，目前没找到雨伞、外套、防晒这类可直接参考的在库物品。"
            itemText.isNotBlank() -> "家里有些候选物品可能用得上，你可以再问我“雨伞/外套/防晒霜在哪儿”，我帮你细找。"
            else -> ""
        }
        val umbrellaText = if (needsUmbrella) "可能有雨，出门最好带伞。" else "看起来不太像要下雨，伞可以按你出门时长决定。"
        val hotText = if (hot) "温度偏高，穿轻薄透气一点，注意补水和防晒。" else "穿常规轻便衣服就行，早晚可以备一件薄外套。"
        return "明天预报：$tomorrow。$hotText $umbrellaText $itemLine".trim()
    }

    private fun isMutationTool(name: String): Boolean {
        return name in setOf(
            "update_item_status",
            "write_item_review",
            "delete_item",
            "add_location",
            "add_scene",
            "delete_location",
            "preview_delete_location_tree",
            "delete_location_tree",
            "add_category",
            "delete_category"
        )
    }

    private fun isSuccessfulMutationResult(result: String): Boolean {
        if (result.isBlank()) return false
        val looksSuccessful = listOf(
            "已",
            "成功",
            "搞定"
        ).any { result.contains(it) }
        val looksFailed = listOf(
            "不能",
            "没有找到",
            "没找到",
            "不明确",
            "失败",
            "请",
            "需要"
        ).any { result.contains(it) }
        return looksSuccessful && !looksFailed
    }

    private fun isVagueToolAnswer(
        content: String,
        toolResults: List<Pair<ToolCall, String>>
    ): Boolean {
        if (toolResults.isEmpty()) return false
        val normalized = content.trim()
        if (normalized.isBlank()) return true
        val vaguePhrases = listOf(
            "我帮你查看",
            "我来查看",
            "我帮你查",
            "我来查",
            "我帮你处理",
            "我来处理",
            "稍等",
            "等我",
            "我先看一下",
            "我先查一下"
        )
        val isVague = vaguePhrases.any { normalized.contains(it) }
        val hasConcreteSignal = listOf(
            "已",
            "找到",
            "没有找到",
            "在\"",
            "在“",
            "库存",
            "天气",
            "位置",
            "分类",
            "评价"
        ).any { normalized.contains(it) }
        return isVague && !hasConcreteSignal
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

    private fun kotlinx.serialization.json.JsonArrayBuilder.addRecentItemContext(context: String?) {
        if (context.isNullOrBlank()) return
        add(
            buildJsonObject {
                put("role", "system")
                put("content", context)
            }
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addLocationTreeContext(context: String?) {
        if (context.isNullOrBlank()) return
        add(
            buildJsonObject {
                put("role", "system")
                put("content", context)
            }
        )
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addToolNudge(context: String?) {
        if (context.isNullOrBlank()) return
        add(
            buildJsonObject {
                put("role", "system")
                put("content", context)
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
- 同一轮对话里，工具返回结果是最高优先级；如果工具说“已添加/已删除/已保存”，就按这个结果回复，不要再根据位置树或历史消息改口说“原本已经存在”。
- 如果工具返回空结果，如实告诉用户没找到，可以建议用户先录入物品。
- search_items 可以搜索名称、分类、位置、备注。
- 当用户用的是泛称、别名、品牌和品类可能不一致的说法时，例如“矿泉水”对应“农夫山泉/怡宝”，“纸巾”对应“抽纸/卷纸”，“充电器”对应“充电头/数据线”，优先调用 find_related_items 做语义候选筛选；如果 search_items 返回空结果，也必须再调用 find_related_items 后才能说没找到。
- find_related_items 返回的是候选池，不是最终结果。你要按用户问题的意思从候选池中挑出真正相关的物品，列出名称、完整位置、分类、数量、状态、备注/过期等关键信息；如果多件都相关，全部列出并请用户选择。
- find_related_items 的 status_scope 要根据用户问题判断：问“在哪儿/有没有/库存里/家里有什么”默认 active；问“用完的/已用完/评价”用 used_up；问“丢弃的”用 discarded；问“全部/不管状态/所有状态”用 all。
- 当用户询问天气、穿衣、饮食、带伞、防晒、冷热、适不适合出门等天气相关问题时，务必先调用 get_weather_context 获取真实天气；如果用户没说城市，city 传空字符串，让工具自动按当前网络定位城市。
- 天气问题在给出穿衣、带伞、防晒、出门携带物等建议时，还要调用 find_weather_items 查库存里相关物品的位置。默认 place_scope 传“家”；如果用户说“宿舍/公司/学校/办公室那边”，place_scope 传用户说的大位置。最终回答里要自然带上“如果要带伞，家里的折叠伞在……”“外套在……”这类位置信息。
- find_weather_items 返回的是候选池，不是最终结果。你要结合天气，只挑用户本次可能需要的东西；如果没找到建议物品，就说没在对应地点找到，不要编造位置。
- 当用户明确要求你修改数据时，才调用修改类工具，例如标记用完/没用完、写评价、删除物品、添加或删除分类、添加或删除存放位置。
- 对所有修改真实数据的请求，必须先调用对应工具，看到工具返回成功后才能说“已完成/搞定/添加好了/删除了/设置好了”。没有工具结果时，绝对不能口头假装已经完成。
- 修改或删除前必须尽量从用户话里提取清楚对象名称；如果工具提示匹配到多个对象或对象不明确，就自然追问，不要编造已经完成。
- 如果用户在连续对话里说“它、这个、那个、刚刚那个、刚才标记的那个、继续给它评分/评价”，应优先沿用系统提示里的“最近一次成功操作的物品”，并直接调用对应修改工具；不要自己根据聊天记录猜测数据库里有没有这件物品。
- 用户说“添加一个宿舍/公司/学校场景”“添加一个新的位置，并推荐/生成下面的位置”“创建一个场景并自动生成里面的位置”时，只调用 add_scene，不要先调用 add_location；场景本质是最外层大位置，里面的位置是它的子位置。用户没列具体子位置时 child_locations 传空数组，让工具按场景名自动推荐。
- 添加/删除位置时要理解“按存放位置”的树结构：最外层是根位置，子位置属于父位置；用户说“在卧室下添加床头柜”时 parent_location 应传“卧室”，用户说“添加宿舍”且没有父位置时 parent_location 传空字符串；用户说“家/家里/我家”时按“我的家”理解。
- 用户在一句话里同时要求多个动作时，例如“添加办公室，并删除宿舍”，必须尽量在同一轮调用多个对应工具；不要只执行其中一个。这个例子应调用 add_scene(name=办公室) 和 preview_delete_location_tree(name=宿舍)，删除动作仍要先预览确认。
- 用户要删除位置/场景/宿舍/办公室/公司这类存放地点时，第一步必须调用 preview_delete_location_tree 预览删除范围并询问确认；用户明确确认后，再调用 delete_location_tree。不要让用户一个子位置一个子位置删。
- delete_location_tree 只删除位置树，不删除物品；原来放在这些位置里的物品会变成未设置位置。回答时要把这一点说清楚。
        """.trimIndent()

        /** OpenAI/DeepSeek function calling 工具定义 */
        private val TOOLS_JSON = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "search_items")
                        put("description", "字面关键词搜索物品，按名称、分类、位置、备注匹配。若用户使用泛称、别名或品类词，例如矿泉水/纸巾/充电器，优先或兜底使用 find_related_items。")
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
                        put("name", "find_related_items")
                        put("description", "语义查找相关物品。用于泛称、同义词、品牌和品类不同名的查询，例如用户问矿泉水在哪，要在候选池里判断农夫山泉、怡宝、百岁山等是否相关。工具返回候选池，最终必须由你按语义挑出相关物品并列出信息。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "用户原始查询里的物品说法，例如矿泉水、纸巾、充电器。")
                                }
                                putJsonObject("status_scope") {
                                    put("type", "string")
                                    put("description", "状态范围：active 表示未用完/在库，used_up 表示已用完，discarded 表示已丢弃，all 表示全部状态。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("query"))
                                add(jsonPrimitive("status_scope"))
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
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "get_weather_context")
                        put("description", "查询真实天气，并返回给智能体用于回答穿衣、饮食、带伞、防晒、出行、冷热等天气相关问题。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("city") {
                                    put("type", "string")
                                    put("description", "用户所在或询问的城市名，例如重庆、杭州、广州。用户没说城市时必须传空字符串，工具会自动按当前网络定位城市。")
                                }
                                putJsonObject("intent") {
                                    put("type", "string")
                                    put("description", "用户的天气相关意图，例如穿衣建议、饮食建议、是否带伞、防晒、适不适合出门。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("city"))
                                add(jsonPrimitive("intent"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "find_weather_items")
                        put("description", "按天气建议查找用户可能需要带或穿的在库物品，并返回它们的位置候选。天气问题调用 get_weather_context 后，应调用本工具来补充“这些东西放在哪儿”。默认查家里；用户说宿舍、公司、办公室等大位置时按用户指定地点查。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("query") {
                                    put("type", "string")
                                    put("description", "结合天气后的物品需求词，例如雨伞 外套 防晒霜 帽子 水杯，或用户原始天气意图。")
                                }
                                putJsonObject("place_scope") {
                                    put("type", "string")
                                    put("description", "要查的存放大位置。默认传“家”；如果用户说宿舍、公司、办公室等，就传对应词。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("query"))
                                add(jsonPrimitive("place_scope"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "update_item_status")
                        put("description", "修改某个物品的状态。仅在用户明确要求标记用完、改回没用完、标记丢弃时调用；如果用户说“它/刚刚那个”，keyword 可以留空，工具会使用最近一次成功操作的物品。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("keyword") {
                                    put("type", "string")
                                    put("description", "要修改的物品名称或关键词；用户指代最近操作的物品时可以留空。")
                                }
                                putJsonObject("status") {
                                    put("type", "string")
                                    put("description", "目标状态：used_up 表示已用完，in_use 表示没用完/在用，discarded 表示丢弃。")
                                }
                                putJsonObject("rating") {
                                    put("type", "integer")
                                    put("description", "可选，1到5星。用户没说评分时不要编造，可省略。")
                                }
                                putJsonObject("review_note") {
                                    put("type", "string")
                                    put("description", "可选，用户给出的评价文字。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("status"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "write_item_review")
                        put("description", "给某个物品保存使用评价；如果物品还没标记用完，会同时标记为已用完。如果用户说“它/刚刚那个/继续评价”，keyword 可以留空，工具会使用最近一次成功操作的物品。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("keyword") {
                                    put("type", "string")
                                    put("description", "要评价的物品名称或关键词；用户指代最近操作的物品时可以留空。")
                                }
                                putJsonObject("rating") {
                                    put("type", "integer")
                                    put("description", "可选，1到5星；用户没说评分时可省略。")
                                }
                                putJsonObject("review_note") {
                                    put("type", "string")
                                    put("description", "评价内容；可以根据用户表达整理成简短评价，不要无中生有。")
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
                        put("name", "delete_item")
                        put("description", "把某个物品移到回收站。仅在用户明确要求删除物品时调用；如果用户说“删掉它/删掉刚刚那个”，keyword 可以留空，工具会使用最近一次成功操作的物品。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("keyword") {
                                    put("type", "string")
                                    put("description", "要删除的物品名称或关键词；用户指代最近操作的物品时可以留空。")
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
                        put("name", "add_location")
                        put("description", "在按存放位置的树结构中添加单个位置。用户只是新增一个位置时调用。若用户还要求推荐/生成下面的一套子位置，必须改用 add_scene，不要先调用本工具。最外层位置可以不传 parent_location 或传空字符串；子位置要传父位置名称或完整路径。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要新增的位置名，例如宿舍、卧室、床头柜。")
                                }
                                putJsonObject("parent_location") {
                                    put("type", "string")
                                    put("description", "父位置名称或完整路径，例如我的家 / 卧室。新增最外层位置时传空字符串。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "add_scene")
                        put("description", "添加一个场景，并自动创建这个场景下面的一组推荐存放位置。场景本质是最外层大位置，例如宿舍、公司、学校、出租屋。用户没指定子位置时 child_locations 传空数组。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "场景名/最外层大位置名，例如宿舍、公司、学校、出租屋。")
                                }
                                putJsonObject("child_locations") {
                                    put("type", "array")
                                    put("description", "要自动创建的子位置列表，例如床铺、书桌、衣柜。用户要求自动推荐时传空数组。也可以传“卧室 / 衣柜”这类路径来创建多级位置。")
                                    putJsonObject("items") {
                                        put("type", "string")
                                    }
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
                                add(jsonPrimitive("child_locations"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "delete_location")
                        put("description", "删除按存放位置中的一个位置。为了安全，工具只会删除没有子位置、没有物品的叶子位置。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要删除的位置名或完整路径。")
                                }
                                putJsonObject("parent_location") {
                                    put("type", "string")
                                    put("description", "可选父位置，用于区分重名位置；不知道时传空字符串。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "preview_delete_location_tree")
                        put("description", "删除存放位置/场景前的预览工具。用户要删除宿舍、办公室、公司、家里某个位置、某个场景时，先调用本工具列出会受影响的子位置和物品，并让用户确认。不要直接删除。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要预览删除的位置名、场景名或完整路径，例如宿舍、办公室、我的家 / 卧室。")
                                }
                                putJsonObject("parent_location") {
                                    put("type", "string")
                                    put("description", "可选父位置，用于区分重名位置；不知道时传空字符串。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "delete_location_tree")
                        put("description", "用户已经确认删除某个位置/场景后调用，一次性删除该位置和所有子位置。只删除位置树，不删除物品；原来放在这些位置下的物品会变成未设置位置。没有预览确认时不要调用。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要删除的位置名/场景名/完整路径。用户只是回复确认、删吧、都删了时可以传空字符串，工具会使用上一轮预览的位置。")
                                }
                                putJsonObject("parent_location") {
                                    put("type", "string")
                                    put("description", "可选父位置，用于区分重名位置；不知道时传空字符串。")
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
                        put("name", "add_category")
                        put("description", "添加一个物品分类。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要新增的分类名。")
                                }
                                putJsonObject("icon") {
                                    put("type", "string")
                                    put("description", "可选图标文本；用户没说时传空字符串。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
                            }
                        }
                    }
                }
            )
            add(
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", "delete_category")
                        put("description", "删除一个物品分类。为了安全，工具只会删除没有物品使用的空分类。")
                        putJsonObject("parameters") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("name") {
                                    put("type", "string")
                                    put("description", "要删除的分类名。")
                                }
                            }
                            putJsonArray("required") {
                                add(jsonPrimitive("name"))
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
