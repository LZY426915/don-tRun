package com.youshu.app.data.repository

import com.youshu.app.BuildConfig
import com.youshu.app.data.local.dao.AiModelConfigDao
import com.youshu.app.data.local.entity.AiModelConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiModelRepository @Inject constructor(
    private val aiModelConfigDao: AiModelConfigDao
) {
    fun getAllModels(): Flow<List<AiModelConfig>> =
        aiModelConfigDao.getAllModels().map { models ->
            models.map { it.withDefaultApiKey() }
        }

    suspend fun getAllModelsSnapshot(): List<AiModelConfig> =
        aiModelConfigDao.getAllModelsSnapshot().map { it.withDefaultApiKey() }

    suspend fun getPrimaryModelForPurpose(purpose: String): AiModelConfig? =
        aiModelConfigDao.getPrimaryModelForPurpose(purpose)?.withDefaultApiKey()

    suspend fun addModel(
        alias: String,
        provider: String,
        endpoint: String,
        modelName: String,
        purpose: String,
        apiKey: String
    ): Long {
        val now = System.currentTimeMillis()
        return aiModelConfigDao.insert(
            AiModelConfig(
                alias = alias,
                provider = provider,
                endpoint = endpoint,
                modelName = modelName,
                purpose = purpose,
                apiKey = apiKey,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateModel(
        id: Long,
        alias: String,
        provider: String,
        endpoint: String,
        modelName: String,
        purpose: String,
        apiKey: String
    ) {
        val existing = aiModelConfigDao.getModelById(id) ?: return
        aiModelConfigDao.update(
            existing.copy(
                alias = alias,
                provider = provider,
                endpoint = endpoint,
                modelName = modelName,
                purpose = purpose,
                apiKey = apiKey,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteModel(id: Long) = aiModelConfigDao.deleteById(id)

    private fun AiModelConfig.withDefaultApiKey(): AiModelConfig {
        if (apiKey.isNotBlank()) return this
        val defaultKey = when (purpose) {
            AiModelConfig.PURPOSE_TEXT_SEARCH -> BuildConfig.DEFAULT_DEEPSEEK_API_KEY
            AiModelConfig.PURPOSE_IMAGE_RECOGNITION -> BuildConfig.DEFAULT_QWEN_API_KEY
            AiModelConfig.PURPOSE_WEATHER -> BuildConfig.DEFAULT_AMAP_WEB_API_KEY
            else -> ""
        }
        return if (defaultKey.isBlank()) this else copy(apiKey = defaultKey)
    }
}
