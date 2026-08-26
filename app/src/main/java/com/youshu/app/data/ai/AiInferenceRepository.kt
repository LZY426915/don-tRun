package com.youshu.app.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.network.BackendApiClient
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
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiInferenceRepository @Inject constructor(
    private val backendApiClient: BackendApiClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseSearchQuery(
        query: String,
        items: List<ItemDetail>
    ): Result<AiSearchCriteria> = withContext(Dispatchers.IO) {
        runCatching {
            val categories = items.mapNotNull { it.categoryName }.distinct().sorted()
            val locations = items.mapNotNull { it.locationName }.distinct().sorted()
            val content = textChatCompletion(
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
            val imageFile = File(imagePath)
            require(imageFile.exists()) { "图片文件不存在" }
            val imageBase64 = encodeImageForVision(imageFile)
            val content = chatCompletion(
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

    suspend fun describeImageForAgent(
        imagePath: String,
        userQuestion: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val imageFile = File(imagePath)
            require(imageFile.exists()) { "图片文件不存在" }
            val imageBase64 = encodeImageForVision(imageFile)
            chatCompletion(
                userContent = buildJsonArray {
                    addText(
                        """
                        你是给家庭物品智能体提供图片理解结果的视觉模型。
                        请根据图片识别画面中的主要物品、文字、状态、包装信息、环境位置和可能风险。
                        用户的问题：${userQuestion?.takeIf { it.isNotBlank() } ?: "请看看这张图片"}

                        输出要求：
                        - 用中文自然语言列出要点，不要返回 JSON。
                        - 看不清或无法确定的内容要明确说不确定。
                        - 如果图片里是食品、药品、儿童用品、电器、清洁剂等，请特别说明可能需要注意的安全点。
                        - 不要替用户做最终决定，只提供给后续文字智能体参考的图片事实和可见线索。
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
        }
    }

    suspend fun transcribeSpeech(
        audioPath: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val audioFile = File(audioPath)
            require(audioFile.exists()) { "语音文件不存在" }
            require(audioFile.length() > 44) { "录音太短，请再说一次" }
            require(audioFile.length() <= MAX_ASR_AUDIO_BYTES) { "语音太长，请控制在 5 分钟以内" }

            val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            val body = buildJsonObject {
                put("stream", false)
                putJsonArray("messages") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "input_audio")
                                        putJsonObject("input_audio") {
                                            put("data", "data:audio/wav;base64,$audioBase64")
                                        }
                                    }
                                )
                            })
                        }
                    )
                }
                putJsonObject("asr_options") {
                    put("language", "zh")
                    put("enable_itn", true)
                }
            }.toString()

            executeChatRequest(QWEN_CHAT_PATH, body, PURPOSE_SPEECH).trim()
                .takeIf { it.isNotBlank() }
                ?: error("语音没有识别出文字")
        }
    }

    private suspend fun textChatCompletion(
        userText: String
    ): String {
        val body = buildJsonObject {
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userText)
                    }
                )
            }
        }.toString()

        return executeChatRequest(DEEPSEEK_CHAT_PATH, body)
    }

    private suspend fun chatCompletion(
        userContent: kotlinx.serialization.json.JsonArray
    ): String {
        val body = buildJsonObject {
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userContent)
                    }
                )
            }
        }.toString()

        return executeChatRequest(QWEN_CHAT_PATH, body, PURPOSE_VISION)
    }

    private suspend fun executeChatRequest(
        path: String,
        body: String,
        purpose: String? = null
    ): String {
        val responseBody = backendApiClient.postJson(path, body, purpose)
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

    private fun encodeImageForVision(
        imageFile: File,
        maxSide: Int = 1600,
        quality: Int = 82
    ): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val bitmap = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)

        val scaled = bitmap.scaleToMaxSide(maxSide)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 95), output)
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxSide || sampledHeight / 2 >= maxSide) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.scaleToMaxSide(maxSide: Int): Bitmap {
        val longSide = maxOf(width, height)
        if (longSide <= maxSide || maxSide <= 0) return this
        val scale = maxSide.toFloat() / longSide.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addText(text: String) {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        )
    }

    private fun String.extractJsonObject(): String {
        val start = indexOf('{')
        val end = lastIndexOf('}')
        require(start >= 0 && end > start) { "AI 返回内容不是 JSON" }
        return substring(start, end + 1)
    }

    private companion object {
        const val DEEPSEEK_CHAT_PATH = "/v1/deepseek/chat/completions"
        const val QWEN_CHAT_PATH = "/v1/qwen/chat/completions"
        const val PURPOSE_VISION = "vision"
        const val PURPOSE_SPEECH = "speech"
        const val MAX_ASR_AUDIO_BYTES = 10 * 1024 * 1024
    }
}
