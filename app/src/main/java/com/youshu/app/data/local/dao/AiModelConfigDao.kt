package com.youshu.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.youshu.app.data.local.entity.AiModelConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface AiModelConfigDao {

    @Query("SELECT * FROM ai_model_configs ORDER BY updatedAt DESC, id DESC")
    fun getAllModels(): Flow<List<AiModelConfig>>

    @Query("SELECT * FROM ai_model_configs ORDER BY updatedAt DESC, id DESC")
    suspend fun getAllModelsSnapshot(): List<AiModelConfig>

    @Query("SELECT * FROM ai_model_configs WHERE id = :id")
    suspend fun getModelById(id: Long): AiModelConfig?

    @Query("SELECT * FROM ai_model_configs WHERE purpose = :purpose ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun getPrimaryModelForPurpose(purpose: String): AiModelConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: AiModelConfig): Long

    @Update
    suspend fun update(model: AiModelConfig)

    @Delete
    suspend fun delete(model: AiModelConfig)

    @Query("DELETE FROM ai_model_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
