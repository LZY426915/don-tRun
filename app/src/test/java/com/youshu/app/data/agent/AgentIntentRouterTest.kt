package com.youshu.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentIntentRouterTest {
    private val router = AgentIntentRouter()

    @Test
    fun route_keepsGeneralReasoningAndEverydayAdviceOutOfTools() {
        assertEquals(AgentRoute.GENERAL, router.route("说谎者悖论到底矛盾在哪里？"))
        assertEquals(AgentRoute.GENERAL, router.route("忒修斯之船还是原来的船吗？"))
        assertEquals(AgentRoute.GENERAL, router.route("全能者能创造自己举不起的石头吗？"))
        assertEquals(AgentRoute.GENERAL, router.route("怎么给空调添加制冷剂？"))
        assertEquals(AgentRoute.GENERAL, router.route("保温杯选什么牌子比较好？"))
    }

    @Test
    fun route_usesAutoToolsForInventoryQuestions() {
        assertEquals(AgentRoute.TOOL_AUTO, router.route("矿泉水在哪儿？"))
        assertEquals(AgentRoute.TOOL_AUTO, router.route("家里有哪些东西快过期？"))
        assertEquals(AgentRoute.TOOL_AUTO, router.route("卧室里都放了什么东西？"))
    }

    @Test
    fun route_requiresToolsForWeatherAndExplicitMutations() {
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("把农夫山泉标记成用完"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("把农夫山泉放到我的家 / 厨房 / 冰箱"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("把农夫山泉的位置改到床头柜"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("帮我把华为耳机换回到书桌"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("把耳机移回书桌"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("明天天气怎么样？"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("添加一个办公室场景"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("添加办公室"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("删除日用品分类"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("删除文件分类"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("评分给5星，写：效果很好", hasRecentItemContext = true))
        assertEquals(AgentRoute.TOOL_AUTO, router.route("帮我评价农夫山泉，给5星"))
        assertEquals(AgentRoute.TOOL_AUTO, router.route("给农夫山泉写个5星评价"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("5星评价", hasRecentItemContext = true))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("给它打5星", hasRecentItemContext = true))
        assertEquals(
            AgentRoute.TOOL_AUTO,
            router.route("帮我给电影《流浪地球》打5星评价", hasRecentItemContext = true)
        )
        assertEquals(
            AgentRoute.TOOL_AUTO,
            router.route("帮我评价农夫山泉，给5星", hasRecentItemContext = true)
        )
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("确认删除"))
    }

    @Test
    fun route_doesNotForceAppToolsForUnrelatedContentEditing() {
        assertEquals(AgentRoute.GENERAL, router.route("把这段文字删除"))
        assertEquals(AgentRoute.GENERAL, router.route("请删除上一条消息"))
        assertEquals(AgentRoute.GENERAL, router.route("把灯光设置为暖色"))
        assertEquals(AgentRoute.GENERAL, router.route("把办公室灯光设置为暖色"))
        assertEquals(AgentRoute.GENERAL, router.route("请把日程改成明天"))
        assertEquals(AgentRoute.GENERAL, router.route("帮我评价一下这篇文章"))
        assertEquals(AgentRoute.GENERAL, router.route("帮我评价电影《星际穿越》"))
        assertEquals(AgentRoute.GENERAL, router.route("帮我评价一下五星酒店"))
    }

    @Test
    fun route_acceptsNaturalDeleteConfirmationPunctuation() {
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("确认删除。"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("好的，确认删除！"))
    }
}
