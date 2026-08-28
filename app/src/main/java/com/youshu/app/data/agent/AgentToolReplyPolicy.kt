package com.youshu.app.data.agent

internal object AgentToolReplyPolicy {
    private val safeLookupTools = setOf(
        "search_items",
        "find_related_items",
        "get_items_by_location",
        "get_expiring_items",
        "get_used_up_items"
    )
    private val unfinishedPhrases = listOf(
        "我帮你查一下",
        "我帮你搜一下",
        "我来查一下",
        "我来搜一下",
        "稍等",
        "等我一下",
        "正在查询",
        "正在搜索"
    )

    fun shouldRetryWithoutTool(
        route: AgentRoute,
        visibleText: String,
        hasToolResults: Boolean
    ): Boolean {
        if (route == AgentRoute.GENERAL || hasToolResults) return false
        return unfinishedPhrases.any(visibleText::contains)
    }

    fun shouldContinueToAllowedTool(
        allowedToolNames: Set<String>?,
        executedToolNames: Set<String>
    ): Boolean {
        if (allowedToolNames.isNullOrEmpty()) return false
        if (executedToolNames.any { it in allowedToolNames }) return false
        return executedToolNames.any(::isSafeUnexpectedLookup)
    }

    fun isSafeUnexpectedLookup(toolName: String): Boolean = toolName in safeLookupTools
}
