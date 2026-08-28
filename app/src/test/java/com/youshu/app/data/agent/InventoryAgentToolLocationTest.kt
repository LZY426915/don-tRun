package com.youshu.app.data.agent

import com.youshu.app.data.local.dao.CategoryDao
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.dao.LocationDao
import com.youshu.app.data.local.entity.Item
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.local.entity.Location
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryAgentToolLocationTest {
    @Test
    fun moveItemToLocation_updatesAndVerifiesTheLeafLocation() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "厨房", parentId = 1),
            Location(id = 3, name = "冰箱", parentId = 2)
        )
        val original = Item(id = 7, name = "农夫山泉", locationId = 1)
        var stored = original

        val itemDao = proxy<ItemDao> { method, args ->
            when (method) {
                "getAllItems" -> flowOf(listOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations))))
                "update" -> {
                    stored = args.first() as Item
                    Unit
                }
                "getItemDetailById" -> flowOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations)))
                else -> error("Unexpected ItemDao call: $method")
            }
        }
        val locationDao = proxy<LocationDao> { method, _ ->
            when (method) {
                "getAllLocationsSnapshot" -> locations
                else -> error("Unexpected LocationDao call: $method")
            }
        }
        val categoryDao = proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") }
        val tool = InventoryAgentTool(itemDao, categoryDao, locationDao)

        val result = tool.moveItemToLocation("农夫山泉", "家 / 厨房 / 冰箱")

        assertEquals(3L, stored.locationId)
        assertTrue(result.contains("已将“农夫山泉”的存放位置修改为“我的家 / 厨房 / 冰箱”"))
    }

    @Test
    fun moveItemToLocation_doesNotClaimSuccessWhenTheDatabaseDidNotChange() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "厨房", parentId = 1),
            Location(id = 3, name = "冰箱", parentId = 2)
        )
        val original = Item(id = 7, name = "农夫山泉", locationId = 1)

        val itemDao = proxy<ItemDao> { method, _ ->
            when (method) {
                "getAllItems" -> flowOf(listOf(ItemDetail(original, locationName = "我的家")))
                "update" -> Unit
                "getItemDetailById" -> flowOf(ItemDetail(original, locationName = "我的家"))
                else -> error("Unexpected ItemDao call: $method")
            }
        }
        val locationDao = proxy<LocationDao> { method, _ ->
            when (method) {
                "getAllLocationsSnapshot" -> locations
                else -> error("Unexpected LocationDao call: $method")
            }
        }
        val categoryDao = proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") }
        val tool = InventoryAgentTool(itemDao, categoryDao, locationDao)

        val result = tool.moveItemToLocation("农夫山泉", "我的家 / 厨房 / 冰箱")

        assertEquals(1L, original.locationId)
        assertTrue(result.contains("修改“农夫山泉”的存放位置失败"))
        assertTrue(!result.contains("已将"))
    }

    @Test
    fun moveItemToLocation_listsEveryLeafBelowANonLeafAndKeepsThePendingItem() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "卧室", parentId = 1),
            Location(id = 3, name = "床头柜", parentId = 2),
            Location(id = 4, name = "衣柜", parentId = 2),
            Location(id = 5, name = "抽屉", parentId = 4)
        )
        val original = Item(id = 7, name = "华为耳机", locationId = 1)
        val itemDao = proxy<ItemDao> { method, _ ->
            when (method) {
                "getAllItems" -> flowOf(listOf(ItemDetail(original, locationName = "我的家")))
                "getItemDetailById" -> flowOf(ItemDetail(original, locationName = "我的家"))
                else -> error("Unexpected ItemDao call: $method")
            }
        }
        val locationDao = proxy<LocationDao> { method, _ ->
            when (method) {
                "getAllLocationsSnapshot" -> locations
                else -> error("Unexpected LocationDao call: $method")
            }
        }
        val tool = InventoryAgentTool(
            itemDao,
            proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") },
            locationDao
        )

        val result = tool.moveItemToLocation("华为耳机", "我的家 / 卧室")

        assertTrue(result.contains("我的家 / 卧室 / 床头柜"))
        assertTrue(result.contains("我的家 / 卧室 / 衣柜 / 抽屉"))
        assertTrue(tool.hasPendingItemLocationMove())
    }

    @Test
    fun moveItemToLocation_acceptsANumberedLeafChoiceFromTheFollowUpMessage() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "卧室", parentId = 1),
            Location(id = 3, name = "床头柜", parentId = 2),
            Location(id = 4, name = "衣柜", parentId = 2)
        )
        var stored = Item(id = 7, name = "华为耳机", locationId = 1)
        val itemDao = proxy<ItemDao> { method, args ->
            when (method) {
                "getAllItems" -> flowOf(listOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations))))
                "getItemDetailById" -> flowOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations)))
                "update" -> {
                    stored = args.first() as Item
                    Unit
                }
                else -> error("Unexpected ItemDao call: $method")
            }
        }
        val locationDao = proxy<LocationDao> { method, _ ->
            when (method) {
                "getAllLocationsSnapshot" -> locations
                else -> error("Unexpected LocationDao call: $method")
            }
        }
        val tool = InventoryAgentTool(
            itemDao,
            proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") },
            locationDao
        )

        tool.moveItemToLocation("华为耳机", "我的家 / 卧室")
        val result = tool.moveItemToLocation("", "第2个")

        assertEquals(4L, stored.locationId)
        assertTrue(result.contains("我的家 / 卧室 / 衣柜"))
        assertTrue(!tool.hasPendingItemLocationMove())
    }

    @Test
    fun pendingLocationMove_onlyAcceptsALeafOrNumberedChoice() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "卧室", parentId = 1),
            Location(id = 3, name = "床头柜", parentId = 2)
        )
        val item = Item(id = 7, name = "华为耳机", locationId = 1)
        val tool = InventoryAgentTool(
            proxy<ItemDao> { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(listOf(ItemDetail(item, locationName = "我的家")))
                    "getItemDetailById" -> flowOf(ItemDetail(item, locationName = "我的家"))
                    else -> error("Unexpected ItemDao call: $method")
                }
            },
            proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") },
            proxy<LocationDao> { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> locations
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )
        tool.moveItemToLocation("华为耳机", "卧室")

        assertTrue(tool.isPendingItemLocationChoice("就放床头柜"))
        assertTrue(tool.isPendingItemLocationChoice("第1个"))
        assertTrue(!tool.isPendingItemLocationChoice("明天天气怎么样"))
    }

    @Test
    fun moveItemToLocation_matchesAUniqueItemWhenTheQueryOmitsWordsInsideItsName() = runTest {
        val locations = listOf(
            Location(id = 1, name = "我的家"),
            Location(id = 2, name = "卧室", parentId = 1),
            Location(id = 3, name = "床头柜", parentId = 2)
        )
        var stored = Item(id = 7, name = "华为蓝牙耳机充电仓", locationId = 1)
        val tool = InventoryAgentTool(
            proxy<ItemDao> { method, args ->
                when (method) {
                    "getAllItems" -> flowOf(listOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations))))
                    "getItemDetailById" -> flowOf(ItemDetail(stored, locationName = pathFor(stored.locationId, locations)))
                    "update" -> {
                        stored = args.first() as Item
                        Unit
                    }
                    else -> error("Unexpected ItemDao call: $method")
                }
            },
            proxy<CategoryDao> { method, _ -> error("Unexpected CategoryDao call: $method") },
            proxy<LocationDao> { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> locations
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )

        val result = tool.moveItemToLocation("华为耳机", "卧室 / 床头柜")

        assertEquals(3L, stored.locationId)
        assertTrue(result.contains("华为蓝牙耳机充电仓"))
    }

    private fun pathFor(locationId: Long?, locations: List<Location>): String? {
        var current = locations.firstOrNull { it.id == locationId } ?: return null
        val names = mutableListOf(current.name)
        while (current.parentId != null) {
            current = locations.first { it.id == current.parentId }
            names += current.name
        }
        return names.asReversed().joinToString(" / ")
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (method: String, args: List<Any?>) -> Any?
    ): T {
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args?.toList().orEmpty())
        } as T
    }
}
