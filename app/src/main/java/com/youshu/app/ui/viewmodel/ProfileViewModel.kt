package com.youshu.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.entity.AiModelConfig
import com.youshu.app.data.repository.AiModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    itemDao: ItemDao,
    private val aiModelRepository: AiModelRepository
) : ViewModel() {

    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

    val totalCount: StateFlow<Int> = itemDao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val activeCount: StateFlow<Int> = itemDao.getActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val usedUpCount: StateFlow<Int> = itemDao.getUsedUpCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L

    val expiringCount: StateFlow<Int> = itemDao
        .getExpiringCount(System.currentTimeMillis() + sevenDaysMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalValue: StateFlow<Double> = itemDao.getTotalValue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val trashCount: StateFlow<Int> = itemDao
        .getRecycleCount(System.currentTimeMillis() - thirtyDaysMs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val aiModels: StateFlow<List<AiModelConfig>> = aiModelRepository.getAllModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addAiModel(
        alias: String,
        provider: String,
        endpoint: String,
        modelName: String,
        purpose: String,
        apiKey: String
    ) {
        val normalizedAlias = alias.trim()
        val normalizedProvider = provider.trim()
        val normalizedEndpoint = endpoint.trim()
        val normalizedModelName = modelName.trim()
        if (
            normalizedAlias.isBlank() ||
            normalizedProvider.isBlank() ||
            normalizedEndpoint.isBlank() ||
            normalizedModelName.isBlank()
        ) {
            return
        }

        viewModelScope.launch {
            aiModelRepository.addModel(
                alias = normalizedAlias,
                provider = normalizedProvider,
                endpoint = normalizedEndpoint,
                modelName = normalizedModelName,
                purpose = purpose,
                apiKey = apiKey.trim()
            )
        }
    }

    fun updateAiModel(
        id: Long,
        alias: String,
        provider: String,
        endpoint: String,
        modelName: String,
        purpose: String,
        apiKey: String
    ) {
        val normalizedAlias = alias.trim()
        val normalizedProvider = provider.trim()
        val normalizedEndpoint = endpoint.trim()
        val normalizedModelName = modelName.trim()
        if (
            normalizedAlias.isBlank() ||
            normalizedProvider.isBlank() ||
            normalizedEndpoint.isBlank() ||
            normalizedModelName.isBlank()
        ) {
            return
        }

        viewModelScope.launch {
            aiModelRepository.updateModel(
                id = id,
                alias = normalizedAlias,
                provider = normalizedProvider,
                endpoint = normalizedEndpoint,
                modelName = normalizedModelName,
                purpose = purpose,
                apiKey = apiKey.trim()
            )
        }
    }

    fun deleteAiModel(id: Long) {
        viewModelScope.launch {
            aiModelRepository.deleteModel(id)
        }
    }
}
