package com.youshu.app.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolReplyPolicyTest {
    @Test
    fun unfinishedSearchPromise_retriesInsteadOfEndingTheTask() {
        assertTrue(
            AgentToolReplyPolicy.shouldRetryWithoutTool(
                route = AgentRoute.TOOL_AUTO,
                visibleText = "我帮你搜一下，请稍等。",
                hasToolResults = false
            )
        )
    }

    @Test
    fun completeGeneralAnswer_doesNotRetry() {
        assertFalse(
            AgentToolReplyPolicy.shouldRetryWithoutTool(
                route = AgentRoute.GENERAL,
                visibleText = "保温杯通常选择食品级不锈钢内胆。",
                hasToolResults = false
            )
        )
    }

    @Test
    fun mutationLookup_isOnlyAnIntermediateStepAndMustContinueToTheAllowedTool() {
        assertTrue(
            AgentToolReplyPolicy.shouldContinueToAllowedTool(
                allowedToolNames = setOf("update_item_location"),
                executedToolNames = setOf("find_related_items")
            )
        )
        assertFalse(
            AgentToolReplyPolicy.shouldContinueToAllowedTool(
                allowedToolNames = setOf("update_item_location"),
                executedToolNames = setOf("update_item_location")
            )
        )
    }

    @Test
    fun unexpectedMutationTool_isNotSafeToExecute() {
        assertTrue(AgentToolReplyPolicy.isSafeUnexpectedLookup("find_related_items"))
        assertFalse(AgentToolReplyPolicy.isSafeUnexpectedLookup("delete_item"))
    }
}
