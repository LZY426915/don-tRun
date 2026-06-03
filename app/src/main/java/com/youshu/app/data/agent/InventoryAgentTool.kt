package com.youshu.app.data.agent

import com.youshu.app.data.local.dao.CategoryDao
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.dao.LocationDao
import com.youshu.app.data.local.entity.Category
import com.youshu.app.data.local.entity.Item
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.local.entity.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 东西不跑 · 小东西智能体数据工具
 *
 * 提供无需 AI 即可直接调用的本地查询函数，返回结构化数据供上层（Agent / UI）使用。
 *
 * 验收标准：
 * - searchItems("剪刀") → 返回剪刀在哪（完整位置路径 + 分类 + 数量 + 过期 + 备注）
 * - getItemsByLocation("卧室") → 返回卧室及子位置下的物品
 * - getExpiringItems(7) → 返回 7 天内快过期的物品
 * - getInventorySummary() → 库存概况
 */
@Singleton
class InventoryAgentTool @Inject constructor(
    private val itemDao: ItemDao,
    private val categoryDao: CategoryDao,
    private val locationDao: LocationDao
) {
    @Volatile
    private var recentOperatedItemId: Long? = null

    // ──────────────────────────────────────────
    // 1. 关键词搜索物品
    // ──────────────────────────────────────────
    /**
     * 按关键词搜索物品，匹配名称 / 分类 / 位置 / 备注。
     */
    fun searchItems(keyword: String): Flow<List<ItemDetail>> {
        return itemDao.searchItems(keyword.trim())
    }

    suspend fun searchItemsSnapshot(keyword: String): List<ItemDetail> {
        return itemDao.searchItems(keyword.trim()).firstOrNull().orEmpty()
    }

    suspend fun getSemanticSearchCandidatesSnapshot(
        query: String,
        statusScope: String
    ): List<ItemDetail> {
        val normalizedScope = statusScope.trim().lowercase()
        val allItems = itemDao.getAllItems().firstOrNull().orEmpty()
        return allItems
            .filter { detail ->
                when (normalizedScope) {
                    "used_up", "used", "已用完", "用完" -> detail.item.status == Item.STATUS_USED_UP
                    "discarded", "丢弃", "废弃" -> detail.item.status == Item.STATUS_DISCARDED
                    "all", "全部", "所有" -> true
                    else -> detail.item.status == Item.STATUS_IN_USE
                }
            }
            .sortedWith(
                compareByDescending<ItemDetail> { semanticHintScore(query, it) }
                    .thenByDescending { it.item.createdAt }
            )
            .take(SEMANTIC_CANDIDATE_LIMIT)
    }

    suspend fun getWeatherItemCandidatesSnapshot(
        query: String,
        placeScope: String
    ): List<ItemDetail> {
        val place = placeScope.trim().ifBlank { "家" }
        val activeItems = itemDao.getAllItems().firstOrNull()
            .orEmpty()
            .filter { it.item.status == Item.STATUS_IN_USE }
        val scopedItems = activeItems.filter { detail ->
            placeMatches(detail.locationName.orEmpty(), place)
        }
        val candidates = scopedItems.ifEmpty { activeItems }
        val weatherQuery = buildWeatherItemQuery(query)
        return candidates
            .sortedWith(
                compareByDescending<ItemDetail> { semanticHintScore(weatherQuery, it) }
                    .thenByDescending { it.item.createdAt }
            )
            .take(SEMANTIC_CANDIDATE_LIMIT)
    }

    // ──────────────────────────────────────────
    // 2. 按位置查询（支持子位置递归）
    // ──────────────────────────────────────────
    /**
     * 根据位置名称返回物品列表。
     * 自动递归子位置：输入"卧室"会返回卧室/衣柜、卧室/床头柜等下面的物品。
     */
    fun getItemsByLocation(locationName: String): Flow<List<ItemDetail>> {
        return itemDao.getItemsByLocationName(locationName.trim())
    }

    suspend fun getItemsByLocationSnapshot(locationName: String): List<ItemDetail> {
        return itemDao.getItemsByLocationName(locationName.trim()).firstOrNull().orEmpty()
    }

    // ──────────────────────────────────────────
    // 3. 按分类查询
    // ──────────────────────────────────────────
    /**
     * 根据分类名称返回物品列表。
     */
    fun getItemsByCategory(categoryName: String): Flow<List<ItemDetail>> {
        return itemDao.getItemsByCategoryName(categoryName.trim())
    }

    suspend fun getItemsByCategorySnapshot(categoryName: String): List<ItemDetail> {
        return itemDao.getItemsByCategoryName(categoryName.trim()).firstOrNull().orEmpty()
    }

    // ──────────────────────────────────────────
    // 4. 即将过期查询
    // ──────────────────────────────────────────
    /**
     * 返回 [days] 天内即将过期的物品列表。
     */
    fun getExpiringItems(days: Int): Flow<List<ItemDetail>> {
        val threshold = System.currentTimeMillis() + daysToMillis(days)
        return itemDao.getExpiringItems(threshold)
    }

    suspend fun getExpiringItemsSnapshot(days: Int): List<ItemDetail> {
        val threshold = System.currentTimeMillis() + daysToMillis(days)
        return itemDao.getExpiringItems(threshold).firstOrNull().orEmpty()
    }

    suspend fun getUsedUpItemsSnapshot(): List<ItemDetail> {
        return itemDao.getAllItems().firstOrNull()
            .orEmpty()
            .filter { it.item.status == Item.STATUS_USED_UP }
    }

    suspend fun getRecentItemContext(): String? {
        val detail = recentItemDetail() ?: return null
        val status = when (detail.item.status) {
            Item.STATUS_IN_USE -> "未用完/在库"
            Item.STATUS_USED_UP -> "已用完"
            Item.STATUS_DISCARDED -> "已丢弃"
            else -> "未知"
        }
        return buildString {
            append("最近一次成功操作的物品是：")
            append(detail.item.name)
            append("；状态：")
            append(status)
            append("；位置：")
            append(detail.locationName ?: "未设置位置")
            append("；分类：")
            append(detail.categoryName ?: "未分类")
            append("。如果用户说“它、这个、那个、刚刚那个、刚才标记的那个、继续给它评分/评价”等省略说法，默认指这件物品；但真正修改数据时仍必须调用对应工具。")
        }
    }

    suspend fun getLocationTreeContext(): String? {
        val locations = locationDao.getAllLocationsSnapshot()
        if (locations.isEmpty()) return null
        val childrenByParent = locations.groupBy { it.parentId }
        val builder = StringBuilder("当前存放位置树（供添加/删除位置时参考，不要直接念给用户）：")

        fun appendChildren(parentId: Long?, depth: Int) {
            childrenByParent[parentId]
                .orEmpty()
                .sortedBy { it.name }
                .forEach { location ->
                    builder.append('\n')
                    repeat(depth) { builder.append("  ") }
                    builder.append("- ")
                    builder.append(location.name)
                    appendChildren(location.id, depth + 1)
                }
        }

        appendChildren(parentId = null, depth = 0)
        builder.append("\n位置规则：用户说“家、家里、我家”时，默认指根位置“我的家”；添加最外层大位置时父位置为空；添加子位置时父位置要传已有位置或完整路径。")
        return builder.toString()
    }

    suspend fun updateItemStatus(
        keyword: String,
        status: String,
        rating: Int?,
        reviewNote: String
    ): String {
        val itemDetail = resolveSingleItem(keyword).getOrElse { return it.message.orEmpty() }
        val item = itemDetail.item
        val normalizedStatus = status.trim().lowercase()
        val now = System.currentTimeMillis()

        return when (normalizedStatus) {
            "used_up", "used", "用完", "已用完" -> {
                val fixedRating = rating?.coerceIn(1, 5) ?: item.rating
                itemDao.update(
                    item.copy(
                        status = Item.STATUS_USED_UP,
                        rating = fixedRating,
                        ratedAt = if (fixedRating != null) now else item.ratedAt,
                        reviewNote = reviewNote.ifBlank { item.reviewNote }
                    )
                )
                rememberItem(item.id)
                "已把“${item.name}”标记为已用完${formatOptionalReview(fixedRating, reviewNote)}。"
            }
            "in_use", "active", "not_used_up", "没用完", "未用完", "在用" -> {
                itemDao.update(
                    item.copy(
                        status = Item.STATUS_IN_USE,
                        rating = null,
                        ratedAt = null,
                        reviewNote = ""
                    )
                )
                rememberItem(item.id)
                "已把“${item.name}”改回未用完状态，原来的评价也已清空。"
            }
            "discarded", "丢弃", "废弃" -> {
                itemDao.update(item.copy(status = Item.STATUS_DISCARDED))
                rememberItem(item.id)
                "已把“${item.name}”标记为已丢弃。"
            }
            else -> "我没理解要把“${item.name}”改成什么状态。可以说：标记用完、改回没用完，或标记丢弃。"
        }
    }

    suspend fun writeItemReview(
        keyword: String,
        rating: Int?,
        reviewNote: String
    ): String {
        val itemDetail = resolveSingleItem(keyword).getOrElse { return it.message.orEmpty() }
        val item = itemDetail.item
        val fixedRating = rating?.coerceIn(1, 5) ?: item.rating
        val note = reviewNote.trim()
        if (fixedRating == null && note.isBlank()) {
            return "要给“${item.name}”写评价的话，请告诉我几星，或者评价内容。"
        }

        itemDao.update(
            item.copy(
                status = Item.STATUS_USED_UP,
                rating = fixedRating,
                ratedAt = System.currentTimeMillis(),
                reviewNote = note.ifBlank { item.reviewNote }
            )
        )
        rememberItem(item.id)
        return "已给“${item.name}”保存评价${formatOptionalReview(fixedRating, note)}。"
    }

    suspend fun deleteItem(keyword: String): String {
        val itemDetail = resolveSingleItem(keyword).getOrElse { return it.message.orEmpty() }
        val item = itemDetail.item
        itemDao.moveToTrash(item.id, System.currentTimeMillis())
        rememberItem(item.id)
        return "已把“${item.name}”移到回收站，30 天内还可以恢复。"
    }

    suspend fun addCategory(name: String, icon: String): String {
        val normalized = name.trim()
        if (normalized.isBlank()) return "分类名不能为空。"
        val categories = categoryDao.getAllCategoriesSnapshot()
        val existing = categories.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        if (existing != null) return "“${existing.name}”这个分类已经存在了。"

        categoryDao.insert(Category(name = normalized, icon = icon.trim()))
        return "已添加物品分类“$normalized”。"
    }

    suspend fun deleteCategory(name: String): String {
        val category = resolveSingleCategory(name).getOrElse { return it.message.orEmpty() }
        val items = itemDao.getAllItems().firstOrNull()
            .orEmpty()
            .filter { it.item.deletedAt == null && it.item.categoryId == category.id }
        if (items.isNotEmpty()) {
            return "“${category.name}”分类下还有 ${items.size} 件物品，先把这些物品改到别的分类或删除后，我再帮你删分类。"
        }

        categoryDao.delete(category)
        return "已删除物品分类“${category.name}”。"
    }

    suspend fun addLocation(
        name: String,
        parentLocation: String
    ): String {
        val addRequest = normalizeAddLocationRequest(name, parentLocation)
        val normalized = addRequest.name
        if (normalized.isBlank()) return "位置名不能为空。"
        val locations = locationDao.getAllLocationsSnapshot()
        val parent = if (addRequest.parentLocation.isBlank()) {
            null
        } else {
            resolveSingleLocation(addRequest.parentLocation, locations).getOrElse { return it.message.orEmpty() }
        }
        val duplicate = locations.firstOrNull {
            it.parentId == parent?.id && it.name.equals(normalized, ignoreCase = true)
        }
        if (duplicate != null) {
            val parentText = parent?.let { "在“${locationPath(it, locations)}”下面" } ?: "在最外层"
            return "$parentText 已经有“${duplicate.name}”这个位置了。"
        }

        locationDao.insert(Location(name = normalized, parentId = parent?.id))
        return if (parent == null) {
            "已在最外层添加位置“$normalized”。"
        } else {
            "已在“${locationPath(parent, locations)}”下面添加位置“$normalized”。"
        }
    }

    suspend fun addScene(
        name: String,
        childLocations: List<String>
    ): String {
        val sceneName = cleanSceneName(name)
        if (sceneName.isBlank()) return "场景名不能为空。"

        val existingLocations = locationDao.getAllLocationsSnapshot().toMutableList()
        var root = existingLocations.firstOrNull {
            it.parentId == null && it.name.equals(sceneName, ignoreCase = true)
        }
        val createdRoot = root == null
        if (root == null) {
            val rootId = locationDao.insert(Location(name = sceneName, parentId = null))
            root = Location(id = rootId, name = sceneName, parentId = null)
            existingLocations += root
        }
        val sceneRoot = requireNotNull(root)

        val recommendedChildren = childLocations
            .map(::normalizeLocationPath)
            .filter { it.isNotBlank() }
            .ifEmpty { defaultSceneLocations(sceneName) }

        val addedPaths = mutableListOf<String>()
        val skippedPaths = mutableListOf<String>()
        recommendedChildren.forEach { childPath ->
            val pathParts = splitLocationPath(childPath).ifEmpty { listOf(childPath) }
            var parent = sceneRoot
            pathParts.forEach { part ->
                val cleanPart = normalizeLocationPath(part)
                if (cleanPart.isBlank()) return@forEach
                val existing = existingLocations.firstOrNull {
                    it.parentId == parent.id && it.name.equals(cleanPart, ignoreCase = true)
                }
                if (existing != null) {
                    skippedPaths += locationPath(existing, existingLocations)
                    parent = existing
                } else {
                    val id = locationDao.insert(Location(name = cleanPart, parentId = parent.id))
                    val created = Location(id = id, name = cleanPart, parentId = parent.id)
                    existingLocations += created
                    addedPaths += locationPath(created, existingLocations)
                    parent = created
                }
            }
        }

        val rootText = if (createdRoot) {
            "已添加场景“$sceneName”"
        } else {
            "“$sceneName”这个场景已经存在，我给它补充缺少的位置"
        }
        val addedText = if (addedPaths.isNotEmpty()) {
            "，并生成了：${addedPaths.joinToString("、")}"
        } else {
            "，推荐位置之前都已经有了"
        }
        val skippedText = if (skippedPaths.isNotEmpty()) {
            "。已有的我没有重复添加。"
        } else {
            "。"
        }
        return rootText + addedText + skippedText
    }

    suspend fun deleteLocation(
        name: String,
        parentLocation: String
    ): String {
        val locations = locationDao.getAllLocationsSnapshot()
        val location = resolveSingleLocation(name, locations, parentLocation.takeIf { it.isNotBlank() })
            .getOrElse { return it.message.orEmpty() }
        val descendants = collectLocationDescendants(location.id, locations)
        val children = locations.filter { it.parentId == location.id }
        if (children.isNotEmpty()) {
            return "“${locationPath(location, locations)}”下面还有 ${children.size} 个子位置。为了避免误删整棵位置树，请先删除或移动子位置。"
        }
        val items = itemDao.getAllItems().firstOrNull()
            .orEmpty()
            .filter { it.item.deletedAt == null && it.item.locationId in descendants }
        if (items.isNotEmpty()) {
            return "“${locationPath(location, locations)}”下面还有 ${items.size} 件物品，先把物品移走或删除后，我再帮你删这个位置。"
        }

        locationDao.delete(location)
        return "已删除位置“${locationPath(location, locations)}”。"
    }

    // ──────────────────────────────────────────
    // 5. 库存概况
    // ──────────────────────────────────────────
    data class InventorySummary(
        val activeItemCount: Int,
        val usedUpCount: Int,
        val totalValue: Double,
        val categoryBreakdown: List<CategoryCount>,
        val locationBreakdown: List<LocationCount>,
        val expiringSoonCount: Int
    )

    data class CategoryCount(
        val categoryName: String,
        val count: Int
    )

    data class LocationCount(
        val locationName: String,
        val count: Int
    )

    suspend fun getInventorySummary(): InventorySummary {
        val activeCount = itemDao.getActiveCount().firstOrNull() ?: 0
        val usedUpCount = itemDao.getUsedUpCount().firstOrNull() ?: 0
        val totalValue = itemDao.getTotalValue().firstOrNull() ?: 0.0
        val sevenDayThreshold = System.currentTimeMillis() + daysToMillis(7)
        val expiringCount = itemDao.getExpiringCount(sevenDayThreshold).firstOrNull() ?: 0

        val categories = categoryDao.getAllCategoriesSnapshot()
        val categoryBreakdown = categories.map { cat ->
            CategoryCount(
                categoryName = cat.name,
                count = itemDao.getCountByCategory(cat.id).firstOrNull() ?: 0
            )
        }.filter { it.count > 0 }.sortedByDescending { it.count }

        val locations = locationDao.getAllLocationsSnapshot()
        val locationBreakdown = locations.map { loc ->
            LocationCount(
                locationName = loc.name,
                count = itemDao.getCountByLocation(loc.id).firstOrNull() ?: 0
            )
        }.filter { it.count > 0 }.sortedByDescending { it.count }

        return InventorySummary(
            activeItemCount = activeCount,
            usedUpCount = usedUpCount,
            totalValue = totalValue,
            categoryBreakdown = categoryBreakdown,
            locationBreakdown = locationBreakdown,
            expiringSoonCount = expiringCount
        )
    }

    private suspend fun resolveSingleItem(keyword: String): Result<ItemDetail> {
        val normalized = keyword.trim()
        if (normalized.isBlank()) {
            return recentItemDetail()?.let { Result.success(it) }
                ?: Result.failure(IllegalArgumentException("请告诉我要操作哪个物品。"))
        }
        val allItems = itemDao.getAllItems().firstOrNull().orEmpty()
        val exact = allItems.filter { it.item.name.equals(normalized, ignoreCase = true) }
        val candidates = exact.ifEmpty {
            allItems.filter { detail ->
                detail.matchesKeyword(normalized)
            }
        }
        if (candidates.isEmpty()) {
            val recent = recentItemDetail()
            if (recent != null && (isRecentItemReference(normalized) || recent.matchesKeyword(normalized))) {
                return Result.success(recent)
            }
        }
        return singleOrFailure(
            candidates = candidates,
            emptyMessage = "没有找到“$normalized”这个物品。",
            ambiguousMessage = buildAmbiguousItemMessage(normalized, candidates)
        )
    }

    private fun rememberItem(itemId: Long) {
        recentOperatedItemId = itemId
    }

    private suspend fun recentItemDetail(): ItemDetail? {
        val itemId = recentOperatedItemId ?: return null
        return itemDao.getItemDetailById(itemId).firstOrNull()
    }

    private fun ItemDetail.matchesKeyword(keyword: String): Boolean {
        val normalized = keyword.trim()
        if (normalized.isBlank()) return false
        return item.name.contains(normalized, ignoreCase = true) ||
            item.note.contains(normalized, ignoreCase = true) ||
            categoryName.orEmpty().contains(normalized, ignoreCase = true) ||
            locationName.orEmpty().contains(normalized, ignoreCase = true)
    }

    private fun semanticHintScore(query: String, detail: ItemDetail): Int {
        val text = listOf(
            detail.item.name,
            detail.item.note,
            detail.categoryName.orEmpty(),
            detail.locationName.orEmpty()
        ).joinToString(" ").lowercase()
        val terms = semanticHintTerms(query)
        var score = 0
        terms.forEach { term ->
            score += when {
                text.contains(term.lowercase()) -> 3
                term.length >= 2 && text.contains(term.take(1), ignoreCase = true) -> 1
                else -> 0
            }
        }
        return score
    }

    private fun semanticHintTerms(query: String): Set<String> {
        val normalized = query.trim().lowercase()
        val terms = mutableSetOf<String>()
        normalized.split(Regex("\\s+|,|，|。|、|\\?|？")).filterTo(terms) { it.isNotBlank() }
        if (normalized.contains("矿泉水") || normalized.contains("瓶装水") || normalized.contains("饮用水")) {
            terms += listOf("水", "农夫山泉", "怡宝", "百岁山", "娃哈哈", "冰露", "泉水", "纯净水", "矿物质水")
        }
        if (normalized.contains("纸巾") || normalized.contains("抽纸") || normalized.contains("卫生纸")) {
            terms += listOf("纸", "纸巾", "抽纸", "卷纸", "卫生纸", "湿巾")
        }
        if (normalized.contains("充电器") || normalized.contains("充电头") || normalized.contains("数据线")) {
            terms += listOf("充电", "充电器", "充电头", "数据线", "线", "插头", "type-c", "typec", "安卓", "苹果")
        }
        if (normalized.contains("避孕套") || normalized.contains("安全套")) {
            terms += listOf("避孕套", "安全套", "套")
        }
        if (normalized.contains("感冒药") || normalized.contains("退烧药") || normalized.contains("药")) {
            terms += listOf("药", "胶囊", "片", "布洛芬", "感冒", "退烧", "止痛")
        }
        return terms
    }

    private fun buildWeatherItemQuery(query: String): String {
        val normalized = query.trim()
        val terms = mutableListOf(normalized)
        if (normalized.contains("雨") || normalized.contains("伞") || normalized.contains("淋") || normalized.contains("湿")) {
            terms += listOf("雨伞", "伞", "折叠伞", "雨衣", "雨鞋", "防水袋")
        }
        if (normalized.contains("冷") || normalized.contains("降温") || normalized.contains("低温") || normalized.contains("风")) {
            terms += listOf("外套", "卫衣", "毛衣", "羽绒服", "围巾", "手套", "帽子", "口罩")
        }
        if (normalized.contains("热") || normalized.contains("高温") || normalized.contains("晒") || normalized.contains("紫外线")) {
            terms += listOf("防晒霜", "防晒", "遮阳伞", "太阳伞", "帽子", "墨镜", "冰袖", "水杯", "矿泉水")
        }
        if (normalized.contains("穿") || normalized.contains("衣")) {
            terms += listOf("外套", "短袖", "长袖", "衬衫", "卫衣", "毛衣", "裤子", "裙子", "袜子", "鞋")
        }
        if (normalized.contains("出门") || normalized.contains("通勤") || normalized.contains("上学") || normalized.contains("上班")) {
            terms += listOf("雨伞", "水杯", "口罩", "纸巾", "充电宝", "钥匙", "帽子")
        }
        return terms.joinToString(" ")
    }

    private fun placeMatches(locationPath: String, placeScope: String): Boolean {
        val place = placeScope.trim()
        if (place.isBlank()) return true
        if (locationPath.isBlank()) return false
        val root = locationPath.split(" / ").firstOrNull().orEmpty()
        return root.equals(place, ignoreCase = true) ||
            root.contains(place, ignoreCase = true) ||
            place.contains(root, ignoreCase = true) ||
            locationPath.contains(place, ignoreCase = true)
    }

    private fun isRecentItemReference(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return true
        return listOf(
            "它",
            "这个",
            "那个",
            "这件",
            "那件",
            "刚刚",
            "刚才",
            "刚标记",
            "刚刚标记",
            "刚才标记",
            "刚刚那个",
            "刚才那个",
            "上一个",
            "上一件",
            "刚刚让你",
            "刚才让你"
        ).any { normalized.contains(it, ignoreCase = true) }
    }

    private suspend fun resolveSingleCategory(name: String): Result<Category> {
        val normalized = name.trim()
        if (normalized.isBlank()) return Result.failure(IllegalArgumentException("请告诉我要操作哪个分类。"))
        val categories = categoryDao.getAllCategoriesSnapshot()
        val exact = categories.filter { it.name.equals(normalized, ignoreCase = true) }
        val candidates = exact.ifEmpty {
            categories.filter { it.name.contains(normalized, ignoreCase = true) }
        }
        return singleOrFailure(
            candidates = candidates,
            emptyMessage = "没有找到“$normalized”这个分类。",
            ambiguousMessage = "找到多个相近分类：${candidates.joinToString("、") { it.name }}。请说清楚要操作哪一个。"
        )
    }

    private fun resolveSingleLocation(
        nameOrPath: String,
        locations: List<Location>,
        parentLocation: String? = null
    ): Result<Location> {
        val normalized = normalizeLocationPath(nameOrPath)
        if (normalized.isBlank()) return Result.failure(IllegalArgumentException("请告诉我要操作哪个位置。"))
        val pathVariants = locationPathVariants(normalized)
        val pathCandidates = locations.filter { location ->
            pathVariants.any { variant ->
                normalizeLocationPath(locationPath(location, locations)).equals(variant, ignoreCase = true)
            }
        }
        val parent = if (!parentLocation.isNullOrBlank()) {
            val parentResult = resolveSingleLocation(parentLocation, locations)
            if (parentResult.isFailure) {
                return Result.failure(
                    IllegalArgumentException(parentResult.exceptionOrNull()?.message.orEmpty())
                )
            }
            parentResult.getOrNull()
        } else {
            null
        }
        val aliasRootCandidates = if (parent == null && isHomeAlias(normalized)) {
            locations.filter { location ->
                location.parentId == null && HOME_ROOT_NAMES.any { location.name.equals(it, ignoreCase = true) }
            }
        } else {
            emptyList()
        }
        if (aliasRootCandidates.size == 1) {
            return Result.success(aliasRootCandidates.first())
        }
        val nameVariants = locationNameVariants(normalized)
        val nameCandidates = locations.filter { location ->
            nameVariants.any { location.name.equals(it, ignoreCase = true) } &&
                (parent == null || location.parentId == parent.id)
        }
        val fuzzyCandidates = if (pathCandidates.isEmpty() && nameCandidates.isEmpty()) {
            locations.filter { location ->
                (nameVariants.any { variant ->
                    location.name.contains(variant, ignoreCase = true) ||
                        (shouldFuzzyMatchPath(variant) &&
                            normalizeLocationPath(locationPath(location, locations)).contains(variant, ignoreCase = true))
                }) &&
                    (parent == null || location.parentId == parent.id)
            }
        } else {
            emptyList()
        }
        val candidates = pathCandidates
            .ifEmpty { aliasRootCandidates }
            .ifEmpty { nameCandidates }
            .ifEmpty { fuzzyCandidates }

        return singleOrFailure(
            candidates = candidates,
            emptyMessage = "没有找到“$normalized”这个位置。",
            ambiguousMessage = "找到多个相近位置：${candidates.joinToString("、") { locationPath(it, locations) }}。请说完整路径，比如“我的家 / 卧室 / 床头柜”。"
        )
    }

    private fun <T> singleOrFailure(
        candidates: List<T>,
        emptyMessage: String,
        ambiguousMessage: String
    ): Result<T> {
        return when (candidates.size) {
            0 -> Result.failure(IllegalArgumentException(emptyMessage))
            1 -> Result.success(candidates.first())
            else -> Result.failure(IllegalArgumentException(ambiguousMessage))
        }
    }

    private fun buildAmbiguousItemMessage(
        keyword: String,
        candidates: List<ItemDetail>
    ): String {
        val preview = candidates.take(6).joinToString("、") { detail ->
            val location = detail.locationName?.let { "（$it）" }.orEmpty()
            "${detail.item.name}$location"
        }
        return "找到多个和“$keyword”相关的物品：$preview。请说清楚要操作哪一个。"
    }

    private fun collectLocationDescendants(
        locationId: Long,
        locations: List<Location>
    ): Set<Long> {
        val childrenMap = locations.groupBy { it.parentId }
        fun collect(id: Long): Set<Long> {
            return setOf(id) + childrenMap[id].orEmpty().flatMap { collect(it.id) }
        }
        return collect(locationId)
    }

    private fun locationPath(
        location: Location,
        locations: List<Location>
    ): String {
        val byId = locations.associateBy { it.id }
        val path = mutableListOf(location.name)
        var parentId = location.parentId
        while (parentId != null) {
            val parent = byId[parentId] ?: break
            path += parent.name
            parentId = parent.parentId
        }
        return path.asReversed().joinToString(" / ")
    }

    private data class AddLocationRequest(
        val name: String,
        val parentLocation: String
    )

    private fun normalizeAddLocationRequest(
        name: String,
        parentLocation: String
    ): AddLocationRequest {
        val normalizedName = normalizeLocationPath(name)
        val normalizedParent = normalizeLocationPath(parentLocation)
        val parts = splitLocationPath(normalizedName)
        return if (normalizedParent.isBlank() && parts.size >= 2) {
            AddLocationRequest(
                name = parts.last(),
                parentLocation = parts.dropLast(1).joinToString(" / ")
            )
        } else {
            AddLocationRequest(
                name = normalizedName,
                parentLocation = normalizedParent
            )
        }
    }

    private fun normalizeLocationPath(value: String): String {
        return value
            .trim()
            .trim('/', '\\', ' ', '　')
            .replace(Regex("\\s*(/|\\\\|>|＞|→|->|—|－)\\s*"), " / ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitLocationPath(value: String): List<String> {
        return normalizeLocationPath(value)
            .split("/")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun locationPathVariants(value: String): List<String> {
        val normalized = normalizeLocationPath(value)
        val parts = splitLocationPath(normalized)
        if (parts.isEmpty()) return emptyList()
        val variants = mutableListOf(normalized)
        if (isHomeAlias(parts.first())) {
            variants += (listOf(HOME_CANONICAL_NAME) + parts.drop(1)).joinToString(" / ")
        }
        return variants.distinct()
    }

    private fun locationNameVariants(value: String): List<String> {
        val normalized = normalizeLocationPath(value)
        return buildList {
            add(normalized)
            if (isHomeAlias(normalized)) add(HOME_CANONICAL_NAME)
        }.distinct()
    }

    private fun isHomeAlias(value: String): Boolean {
        val normalized = normalizeLocationPath(value)
            .replace(" ", "")
            .trim('的')
        return normalized in HOME_ALIASES
    }

    private fun shouldFuzzyMatchPath(value: String): Boolean {
        val normalized = normalizeLocationPath(value).replace(" ", "")
        return normalized.length >= 2 && !isHomeAlias(normalized)
    }

    private fun formatOptionalReview(
        rating: Int?,
        reviewNote: String
    ): String {
        val parts = buildList {
            rating?.let { add("${it}星") }
            reviewNote.trim().takeIf { it.isNotBlank() }?.let { add("评价：$it") }
        }
        return if (parts.isEmpty()) "" else "，${parts.joinToString("，")}"
    }

    private fun cleanSceneName(value: String): String {
        return normalizeLocationPath(value)
            .replace(Regex("(场景|大位置|地点|地方|区域)$"), "")
            .trim()
    }

    private fun defaultSceneLocations(sceneName: String): List<String> {
        val normalized = sceneName.replace(" ", "")
        return when {
            normalized.contains("宿舍") -> listOf("床铺", "书桌", "衣柜", "床头柜", "收纳柜", "阳台", "卫生间")
            normalized.contains("公司") || normalized.contains("办公室") -> listOf("工位", "抽屉", "文件柜", "茶水间", "会议室", "储物柜")
            normalized.contains("学校") || normalized.contains("教室") -> listOf("课桌", "书包", "储物柜", "图书馆", "实验室")
            normalized.contains("厨房") -> listOf("冰箱", "橱柜", "调料区", "水槽下", "餐具柜")
            normalized.contains("车") -> listOf("后备箱", "手套箱", "扶手箱", "车门储物格")
            normalized.contains("家") || normalized.contains("出租屋") || normalized.contains("租房") -> {
                listOf("卧室", "客厅", "厨房", "卫生间", "阳台", "玄关", "储物间")
            }
            else -> listOf("入口", "常用区", "收纳区", "备用区")
        }
    }

    companion object {
        private const val SEMANTIC_CANDIDATE_LIMIT = 120
        private const val HOME_CANONICAL_NAME = "我的家"
        private val HOME_ROOT_NAMES = setOf(HOME_CANONICAL_NAME)
        private val HOME_ALIASES = setOf("家", "家里", "家中", "我家", "我的家", "家里面", "家里头")

        private fun daysToMillis(days: Int): Long = days * 24L * 60 * 60 * 1000

        fun formatItemLocation(item: ItemDetail): String {
            val loc = item.locationName ?: "未设置位置"
            return "$loc → ${item.item.name}（${item.item.quantity}${item.item.unit}）"
        }
    }
}
