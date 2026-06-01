package com.youshu.app.data.ai

import android.util.Base64
import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.repository.AiModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiInferenceRepository @Inject constructor(
    private val aiModelRepository: AiModelRepository
) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun parseSearchQuery(
        query: String,
        items: List<ItemDetail>
    ): Result<AiSearchCriteria> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig(AiModelConfig.PURPOSE_TEXT_SEARCH)
            val categories = items.mapNotNull { it.categoryName }.distinct().sorted()
            val locations = items.mapNotNull { it.locationName }.distinct().sorted()
            val content = textChatCompletion(
                config = config,
                userText = """
                请把用户的物品搜索语句解析成 JSON，只返回 JSON。
                可用分类：${categories.joinToString("、").ifBlank { "无" }}
                可用位置：${locations.joinToString("、").ifBlank { "无" }}
                status 只能是 all、used_up、pending_review、reviewed 或 null。
                expiringSoon 表示用户是否在找即将过期/快过期物品。
                JSON 格式：
                {"keyword":"物品关键词","categoryName":null,"locationName":null,"status":null,"expiringSoon":false}
                用户搜索：$query
                """.trimIndent()
            )
            val obj = json.parseToJsonElement(content.extractJsonObject()).jsonObject
            AiSearchCriteria(
                keyword = obj["keyword"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                categoryName = obj["categoryName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                locationName = obj["locationName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                status = obj["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "all" },
                expiringSoon = obj["expiringSoon"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    suspend fun recognizeImage(
        imagePath: String,
        categoryNames: List<String>,
        locationNames: List<String>
    ): Result<AiImageRecognitionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val config = requireConfig(AiModelConfig.PURPOSE_IMAGE_RECOGNITION)
            val imageFile = File(imagePath)
            require(imageFile.exists()) { "图片文件不存在" }
            val imageBase64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
            val content = chatCompletion(
                config = config,
                userContent = buildJsonArray {
                    addText(
                        """
                        请识别图片中的家庭物品，并只返回 JSON。
                        可用分类：${categoryNames.joinToString("、").ifBlank { "无" }}
                        可用位置：${locationNames.joinToString("、").ifBlank { "无" }}
                        categoryName 必须从可用分类中选择一个最匹配的名称并原样返回；只有可用分类为空时才返回 null。
                        locationName 请优先根据图片场景和物品常见存放习惯，从可用位置中选择一个最可能的位置并原样返回；只有可用位置为空时才返回 null。
                        如果无法确定，请留空或用 null。expireDays 是从今天起的保质期天数。
                        JSON 格式：
                        {"name":"物品名称","categoryName":null,"locationName":null,"quantity":1,"unit":"件","expireDays":null,"note":"简短备注"}
                        """.trimIndent()
                    )
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            }
                        }
                    )
                }
            )
            val obj = json.parseToJsonElement(content.extractJsonObject()).jsonObject
            AiImageRecognitionResult(
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                categoryName = obj["categoryName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                locationName = obj["locationName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                quantity = obj["quantity"]?.jsonPrimitive?.intOrNull,
                unit = obj["unit"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                expireDays = obj["expireDays"]?.jsonPrimitive?.intOrNull,
                note = obj["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            )
        }
    }

    private suspend fun requireConfig(purpose: String): AiModelConfig {
        val config = aiModelRepository.getPrimaryModelForPurpose(purpose)
            ?: error("请先在 AI 模型管理中配置${AiModelConfig.purposeLabel(purpose)}模型")
        require(config.apiKey.isNotBlank()) {
            "请先在 AI 模型管理中填写${AiModelConfig.purposeLabel(purpose)}的 API Key"
        }
        require(config.modelName.isNotBlank()) {
            "请先在 AI 模型管理中填写${AiModelConfig.purposeLabel(purpose)}的模型名称"
        }
        return config
    }

    private fun textChatCompletion(
        config: AiModelConfig,
        userText: String
    ): String {
        val body = buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userText)
                    }
                )
            }
        }.toString().toRequestBody(mediaType)

        return executeChatRequest(config, body)
    }

    private fun chatCompletion(
        config: AiModelConfig,
        userContent: kotlinx.serialization.json.JsonArray
    ): String {
        val body = buildJsonObject {
            put("model", config.modelName)
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userContent)
                    }
                )
            }
        }.toString().toRequestBody(mediaType)

        return executeChatRequest(config, body)
    }

    private fun executeChatRequest(
        config: AiModelConfig,
        body: okhttp3.RequestBody
    ): String {
        val response = client.newCall(
            Request.Builder()
                .url(config.endpoint.chatCompletionsUrl())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                error("AI 请求失败：HTTP ${it.code}")
            }
            val responseBody = it.body?.string().orEmpty()
            val obj = json.parseToJsonElement(responseBody).jsonObject
            return obj["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { content -> content.isNotBlank() }
                ?: error("AI 没有返回可用内容")
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addText(text: String) {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        )
    }

    private fun String.chatCompletionsUrl(): String {
        val normalized = trim().trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) {
            normalized
        } else {
            "$normalized/chat/completions"
        }
    }

    private fun String.extractJsonObject(): String {
        val start = indexOf('{')
        val end = lastIndexOf('}')
        require(start >= 0 && end > start) { "AI 返回内容不是 JSON" }
        return substring(start, end + 1)
    }
}
