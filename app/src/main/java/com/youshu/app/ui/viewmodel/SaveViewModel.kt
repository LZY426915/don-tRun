package com.youshu.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youshu.app.data.ai.AiInferenceRepository
import com.youshu.app.data.local.entity.Category
import com.youshu.app.data.local.entity.Item
import com.youshu.app.data.local.entity.Location
import com.youshu.app.data.repository.CategoryRepository
import com.youshu.app.data.repository.ItemRepository
import com.youshu.app.data.repository.LocationRepository
import com.youshu.app.util.ImageUtil
import com.youshu.app.util.DateUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class SaveItemState(
    val name: String = "",
    val categoryId: Long? = null,
    val locationId: Long? = null,
    val quantity: Int = 1,
    val unit: String = "件",
    val price: String = "",
    val expireTime: Long? = null,
    val note: String = "",
    val imagePaths: List<String> = emptyList(),
    val isAiRecognizing: Boolean = false,
    val aiMessage: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class SaveViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val locationRepository: LocationRepository,
    private val aiInferenceRepository: AiInferenceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SaveItemState())
    val state: StateFlow<SaveItemState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<Location>> = locationRepository.getAllLocations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initFromPhoto(context: Context, imageUri: Uri?, aiName: String?, aiCategory: String?) {
        val current = _state.value
        if (imageUri == null || current.imagePaths.isNotEmpty()) return

        val savedPath = ImageUtil.saveImageToInternal(context, imageUri)
        _state.value = current.copy(
            name = aiName ?: "",
            imagePaths = savedPath?.let(::listOf).orEmpty()
        )
        savedPath?.let(::recognizePrimaryPhoto)
        if (aiCategory != null) {
            viewModelScope.launch {
                categories.value.find { it.name == aiCategory }?.let { category ->
                    _state.value = _state.value.copy(categoryId = category.id)
                }
            }
        }
    }

    fun initFromPhotos(context: Context, imageUris: List<Uri>, aiName: String?, aiCategory: String?) {
        val current = _state.value
        if (imageUris.isEmpty() || current.imagePaths.isNotEmpty()) return

        val savedPaths = imageUris.mapNotNull { ImageUtil.saveImageToInternal(context, it) }.distinct()
        _state.value = current.copy(
            name = aiName ?: current.name,
            imagePaths = savedPaths
        )
        savedPaths.firstOrNull()?.let(::recognizePrimaryPhoto)
        if (aiCategory != null) {
            viewModelScope.launch {
                categories.value.find { it.name == aiCategory }?.let { category ->
                    _state.value = _state.value.copy(categoryId = category.id)
                }
            }
        }
    }

    fun appendPhotos(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val savedPaths = uris.mapNotNull { ImageUtil.saveImageToInternal(context, it) }
        if (savedPaths.isEmpty()) return
        _state.value = _state.value.copy(
            imagePaths = (_state.value.imagePaths + savedPaths).distinct()
        )
    }

    fun removePhoto(index: Int) {
        val currentPaths = _state.value.imagePaths.toMutableList()
        val removed = currentPaths.getOrNull(index) ?: return
        currentPaths.removeAt(index)
        ImageUtil.deleteImage(removed)
        _state.value = _state.value.copy(imagePaths = currentPaths)
    }

    fun updateName(name: String) {
        _state.value = _state.value.copy(name = name)
    }

    fun updateCategory(categoryId: Long?) {
        _state.value = _state.value.copy(categoryId = categoryId)
    }

    fun updateLocation(locationId: Long?) {
        _state.value = _state.value.copy(locationId = locationId)
    }

    fun updateQuantity(quantity: Int) {
        _state.value = _state.value.copy(quantity = quantity.coerceAtLeast(1))
    }

    fun updateUnit(unit: String) {
        _state.value = _state.value.copy(unit = unit)
    }

    fun updatePrice(price: String) {
        _state.value = _state.value.copy(price = price)
    }

    fun updateExpireTime(time: Long?) {
        _state.value = _state.value.copy(expireTime = time)
    }

    fun updateNote(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    fun consumeAiMessage() {
        _state.value = _state.value.copy(aiMessage = null)
    }

    fun recognizePrimaryPhoto() {
        _state.value.imagePaths.firstOrNull()?.let(::recognizePrimaryPhoto)
    }

    private fun recognizePrimaryPhoto(imagePath: String) {
        if (_state.value.isAiRecognizing) return
        _state.value = _state.value.copy(isAiRecognizing = true, aiMessage = "正在用 AI 识别图片")
        viewModelScope.launch {
            val availableCategories = awaitCategories()
            val availableLocations = awaitLocations()
            val leafLocations = availableLocations.leafLocations()
            aiInferenceRepository.recognizeImage(
                imagePath = imagePath,
                categoryNames = availableCategories.map { it.name },
                locationNames = leafLocations.map { it.pathLabel(availableLocations) }
            )
                .onSuccess { result ->
                    val matchedCategory = result.categoryName?.let { predicted ->
                        findBestCategory(availableCategories, predicted)
                    }
                    val matchedLocation = result.locationName?.let { predicted ->
                        findBestLocation(leafLocations, predicted)
                    }
                    val current = _state.value
                    _state.value = current.copy(
                        name = result.name.takeIf { it.isNotBlank() } ?: current.name,
                        categoryId = matchedCategory?.id ?: current.categoryId,
                        locationId = matchedLocation?.id ?: current.locationId,
                        quantity = result.quantity?.coerceAtLeast(1) ?: current.quantity,
                        unit = result.unit ?: current.unit,
                        expireTime = result.expireDays
                            ?.takeIf { it > 0 }
                            ?.toLong()
                            ?.let(DateUtil::daysFromNow) ?: current.expireTime,
                        note = result.note ?: current.note,
                        isAiRecognizing = false,
                        aiMessage = "AI 已填入识别结果"
                    )
                }
                .onFailure { throwable ->
                    _state.value = _state.value.copy(
                        isAiRecognizing = false,
                        aiMessage = throwable.message ?: "AI 图片识别失败"
                    )
                }
        }
    }

    private suspend fun awaitCategories(): List<Category> {
        categories.value.takeIf { it.isNotEmpty() }?.let { return it }
        return withTimeoutOrNull(1_500) {
            categories.first { it.isNotEmpty() }
        } ?: categories.value
    }

    private suspend fun awaitLocations(): List<Location> {
        locations.value.takeIf { it.isNotEmpty() }?.let { return it }
        return withTimeoutOrNull(1_500) {
            locations.first { it.isNotEmpty() }
        } ?: locations.value
    }

    private fun findBestCategory(categories: List<Category>, predicted: String): Category? {
        return findBestNamedItem(categories, predicted) { it.name }
    }

    private fun findBestLocation(locations: List<Location>, predicted: String): Location? {
        return findBestNamedItem(locations, predicted) { it.pathLabel(locations) }
            ?: findBestNamedItem(locations, predicted) { it.name }
    }

    private fun <T> findBestNamedItem(items: List<T>, predicted: String, nameOf: (T) -> String): T? {
        val normalizedPredicted = predicted.normalizedAiLabel()
        if (normalizedPredicted.isBlank()) return null
        return items.firstOrNull { nameOf(it).normalizedAiLabel() == normalizedPredicted }
            ?: items.firstOrNull { item ->
                val name = nameOf(item).normalizedAiLabel()
                name.isNotBlank() && (name.contains(normalizedPredicted) || normalizedPredicted.contains(name))
            }
    }

    private fun String.normalizedAiLabel(): String {
        return trim()
            .replace("分类", "")
            .replace("类别", "")
            .replace("类", "")
            .replace("位置", "")
            .replace("/", "")
            .replace(">", "")
            .replace("、", "")
            .replace(" ", "")
            .lowercase()
    }

    private fun Location.pathLabel(locations: List<Location>): String {
        val locationById = locations.associateBy { it.id }
        val path = mutableListOf(name)
        var currentParentId = parentId
        while (currentParentId != null) {
            val parent = locationById[currentParentId] ?: break
            path += parent.name
            currentParentId = parent.parentId
        }
        return path.asReversed().joinToString(" / ")
    }

    private fun List<Location>.leafLocations(): List<Location> {
        val parentIds = mapNotNull { it.parentId }.toSet()
        return filter { it.id !in parentIds }
    }

    private fun List<Location>.isLeafLocation(locationId: Long): Boolean {
        return none { it.parentId == locationId }
    }

    fun save() {
        val current = _state.value
        if (
            current.name.isBlank() ||
            current.categoryId == null ||
            current.locationId == null ||
            !locations.value.isLeafLocation(current.locationId) ||
            current.isSaving
        ) return

        _state.value = current.copy(isSaving = true)
        viewModelScope.launch {
            val normalizedImages = current.imagePaths.distinct()
            val item = Item(
                name = current.name,
                categoryId = current.categoryId,
                locationId = current.locationId,
                quantity = current.quantity,
                unit = current.unit,
                price = current.price.toDoubleOrNull(),
                expireTime = current.expireTime,
                note = current.note,
                imagePath = normalizedImages.firstOrNull().orEmpty(),
                imagePaths = Item.encodeImagePaths(normalizedImages)
            )
            itemRepository.insert(item)
            _state.value = _state.value.copy(isSaving = false, isSaved = true)
        }
    }

    fun reset() {
        _state.value = SaveItemState()
    }
}
