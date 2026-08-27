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
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("明天天气怎么样？"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("添加一个办公室场景"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("删除日用品分类"))
        assertEquals(AgentRoute.TOOL_REQUIRED, router.route("确认删除"))
    }

    @Test
    fun route_doesNotForceAppToolsForUnrelatedContentEditing() {
        assertEquals(AgentRoute.GENERAL, router.route("把这段文字删除"))
        assertEquals(AgentRoute.GENERAL, router.route("请删除上一条消息"))
    }
}
