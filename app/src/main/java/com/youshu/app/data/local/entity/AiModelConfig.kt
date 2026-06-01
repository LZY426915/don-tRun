package com.youshu.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_model_configs")
data class AiModelConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val alias: String,
    val provider: String,
    val endpoint: String,
    val modelName: String = "",
    val purpose: String = PURPOSE_TEXT_SEARCH,
    val apiKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PURPOSE_TEXT_SEARCH = "text_search"
        const val PURPOSE_IMAGE_RECOGNITION = "image_recognition"

        fun purposeLabel(purpose: String): String {
            return when (purpose) {
                PURPOSE_IMAGE_RECOGNITION -> "图片识别"
                else -> "文字搜索"
            }
        }
    }
}
