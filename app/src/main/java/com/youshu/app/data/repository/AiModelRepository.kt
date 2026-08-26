package com.youshu.app.data.repository

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
        aiModelConfigDao.getAllModels().map { models -> models.map { it.withoutApiKey() } }

    suspend fun getAllModelsSnapshot(): List<AiModelConfig> =
        aiModelConfigDao.getAllModelsSnapshot().map { it.withoutApiKey() }

    suspend fun getPrimaryModelForPurpose(purpose: String): AiModelConfig? =
        aiModelConfigDao.getPrimaryModelForPurpose(purpose)?.withoutApiKey()

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

    private fun AiModelConfig.withoutApiKey(): AiModelConfig =
        if (apiKey.isEmpty()) this else copy(apiKey = "")
}
