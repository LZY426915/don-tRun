package com.youshu.app.data.agent

import com.youshu.app.data.local.dao.CategoryDao
import com.youshu.app.data.local.dao.ItemDao
import com.youshu.app.data.local.dao.LocationDao
import com.youshu.app.data.local.entity.Category
import com.youshu.app.data.local.entity.Item
import com.youshu.app.data.local.entity.ItemDetail
import com.youshu.app.data.local.entity.Location
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryAgentToolMutationVerificationTest {
    private val home = Location(id = 1, name = "我的家")
    private val item = Item(id = 7, name = "耳机", locationId = home.id)

    @Test
    fun updateItemStatus_doesNotClaimSuccessWhenUpdateIsNotReflected() = runTest {
        val stored = item
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(listOf(ItemDetail(stored, locationName = home.name)))
                    "update" -> Unit
                    "getItemDetailById" -> flowOf(ItemDetail(stored, locationName = home.name))
                    else -> error("Unexpected ItemDao call: $method")
                }
            }
        )

        val result = tool.updateItemStatus("耳机", "used_up", 5, "")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已把"))
    }

    @Test
    fun writeItemReview_doesNotClaimSuccessWhenUpdateIsNotReflected() = runTest {
        val stored = item
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(listOf(ItemDetail(stored, locationName = home.name)))
                    "update" -> Unit
                    "getItemDetailById" -> flowOf(ItemDetail(stored, locationName = home.name))
                    else -> error("Unexpected ItemDao call: $method")
                }
            }
        )

        val result = tool.writeItemReview("耳机", 5, "效果很好")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已给"))
    }

    @Test
    fun deleteItem_doesNotClaimSuccessWhenTrashMoveIsNotReflected() = runTest {
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(listOf(ItemDetail(item, locationName = home.name)))
                    "moveToTrash" -> Unit
                    "getItemById" -> item
                    else -> error("Unexpected ItemDao call: $method")
                }
            }
        )

        val result = tool.deleteItem("耳机")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已把"))
    }

    @Test
    fun addCategory_doesNotClaimSuccessWhenInsertIsNotReflected() = runTest {
        val tool = tool(
            categoryDao = proxy { method, _ ->
                when (method) {
                    "getAllCategoriesSnapshot" -> emptyList<Category>()
                    "insert" -> 11L
                    "getCategoryById" -> null
                    else -> error("Unexpected CategoryDao call: $method")
                }
            }
        )

        val result = tool.addCategory("QA分类", "")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已添加"))
    }

    @Test
    fun addLocation_doesNotClaimSuccessWhenInsertIsNotReflected() = runTest {
        val tool = tool(
            locationDao = proxy { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> listOf(home)
                    "insert" -> 12L
                    "getLocationById" -> null
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )

        val result = tool.addLocation("QA位置", "我的家")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已在"))
    }

    @Test
    fun addScene_doesNotClaimSuccessWhenInsertIsNotReflected() = runTest {
        var nextId = 20L
        val tool = tool(
            locationDao = proxy { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> emptyList<Location>()
                    "insert" -> nextId++
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )

        val result = tool.addScene("QA场景", listOf("桌面"))

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已添加场景"))
    }

    @Test
    fun deleteCategory_doesNotClaimSuccessWhenDeleteIsNotReflected() = runTest {
        val category = Category(id = 13, name = "QA分类")
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(emptyList<ItemDetail>())
                    else -> error("Unexpected ItemDao call: $method")
                }
            },
            categoryDao = proxy { method, _ ->
                when (method) {
                    "getAllCategoriesSnapshot" -> listOf(category)
                    "delete" -> Unit
                    "getCategoryById" -> category
                    else -> error("Unexpected CategoryDao call: $method")
                }
            }
        )

        val result = tool.deleteCategory("QA分类")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已删除"))
    }

    @Test
    fun deleteLocation_doesNotClaimSuccessWhenDeleteIsNotReflected() = runTest {
        val shelf = Location(id = 14, name = "书柜", parentId = home.id)
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(emptyList<ItemDetail>())
                    else -> error("Unexpected ItemDao call: $method")
                }
            },
            locationDao = proxy { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> listOf(home, shelf)
                    "delete" -> Unit
                    "getLocationById" -> shelf
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )

        val result = tool.deleteLocation("书柜", "我的家")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已删除位置"))
    }

    @Test
    fun deleteLocationTree_doesNotClaimSuccessWhenDeleteIsNotReflected() = runTest {
        val scene = Location(id = 15, name = "QA场景")
        val child = Location(id = 16, name = "桌面", parentId = scene.id)
        val locations = listOf(scene, child)
        val tool = tool(
            itemDao = proxy { method, _ ->
                when (method) {
                    "getAllItems" -> flowOf(emptyList<ItemDetail>())
                    else -> error("Unexpected ItemDao call: $method")
                }
            },
            locationDao = proxy { method, _ ->
                when (method) {
                    "getAllLocationsSnapshot" -> locations
                    "delete" -> Unit
                    else -> error("Unexpected LocationDao call: $method")
                }
            }
        )

        tool.previewDeleteLocationTree("QA场景", "")
        val result = tool.deleteLocationTree("", "")

        assertTrue(result.contains("失败"))
        assertTrue(!result.contains("已删除位置树"))
    }

    private fun tool(
        itemDao: ItemDao = proxy { method, _ -> error("Unexpected ItemDao call: $method") },
        categoryDao: CategoryDao = proxy { method, _ -> error("Unexpected CategoryDao call: $method") },
        locationDao: LocationDao = proxy { method, _ -> error("Unexpected LocationDao call: $method") }
    ) = InventoryAgentTool(itemDao, categoryDao, locationDao)

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (method: String, args: List<Any?>) -> Any?
    ): T {
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args?.toList().orEmpty())
        } as T
    }
}
