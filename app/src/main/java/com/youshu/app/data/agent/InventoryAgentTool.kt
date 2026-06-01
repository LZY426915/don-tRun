package com.youshu.app.data.agent

import com.youshu.app.data.local.dao.CategoryDao
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.dao.LocationDao
import com.youshu.app.data.local.entity.ItemDetail
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

    companion object {
        private fun daysToMillis(days: Int): Long = days * 24L * 60 * 60 * 1000

        fun formatItemLocation(item: ItemDetail): String {
            val loc = item.locationName ?: "未设置位置"
            return "$loc → ${item.item.name}（${item.item.quantity}${item.item.unit}）"
        }
    }
}
