package com.youshu.app.data.agent

enum class AgentRoute {
    GENERAL,
    TOOL_AUTO,
    TOOL_REQUIRED
}

internal class AgentIntentRouter {
    fun route(message: String): AgentRoute {
        val text = message.replace(Regex("\\s+"), "").trim()
        if (text.isBlank()) return AgentRoute.GENERAL
        if (isUserLocationMetaQuestion(text)) return AgentRoute.GENERAL
        if (WEATHER_TERMS.any(text::contains)) return AgentRoute.TOOL_REQUIRED
        if (isDeleteConfirmation(text)) return AgentRoute.TOOL_REQUIRED
        if (isUnrelatedContentMutation(text)) return AgentRoute.GENERAL
        if (isExplicitMutation(text)) return AgentRoute.TOOL_REQUIRED
        if (isInventoryQuery(text)) return AgentRoute.TOOL_AUTO
        return AgentRoute.GENERAL
    }

    private fun isExplicitMutation(text: String): Boolean {
        if (REVIEW_TERMS.any(text::contains)) return true
        if (STATUS_TERMS.any(text::contains) && MUTATION_TERMS.any(text::contains)) return true

        val hasMutation = MUTATION_TERMS.any(text::contains)
        if (!hasMutation) return false
        val hasAppDomain = APP_DOMAIN_TERMS.any(text::contains)
        val looksLikeDirectCommand = COMMAND_PREFIXES.any(text::startsWith) || text.contains("帮我")
        return hasAppDomain || (looksLikeDirectCommand && !looksLikeGeneralHowTo(text))
    }

    private fun isDeleteConfirmation(text: String): Boolean = text in setOf(
        "确认删除",
        "确定删除",
        "同意删除",
        "可以删",
        "删吧",
        "都删了",
        "继续删"
    )

    private fun isUnrelatedContentMutation(text: String): Boolean {
        val hasMutation = MUTATION_TERMS.any(text::contains)
        return hasMutation && listOf(
            "文字",
            "文本",
            "段落",
            "句子",
            "消息",
            "聊天记录",
            "文件",
            "文档"
        ).any(text::contains)
    }

    private fun looksLikeGeneralHowTo(text: String): Boolean {
        if (!text.startsWith("怎么") && !text.startsWith("如何")) return false
        return APP_DOMAIN_TERMS.none(text::contains) && STATUS_TERMS.none(text::contains)
    }

    private fun isInventoryQuery(text: String): Boolean {
        if (listOf("矛盾在哪", "问题在哪", "原因在哪", "意义在哪", "区别在哪").any(text::contains)) {
            return false
        }
        if (listOf("过期", "临期", "库存", "已用完", "用完清单").any(text::contains)) {
            return true
        }
        val asksLocation = listOf("在哪", "哪里", "放哪", "找不到").any(text::contains)
        val asksContents = listOf("有什么东西", "有哪些东西", "放了什么", "存了什么").any(text::contains)
        val mentionsInventory = listOf("家里", "宿舍", "卧室", "厨房", "库房", "物品", "东西").any(text::contains)
        return asksLocation || asksContents || (mentionsInventory && listOf("有没有", "哪些", "清单").any(text::contains))
    }

    private fun isUserLocationMetaQuestion(text: String): Boolean = listOf(
        "我在哪",
        "我在哪里",
        "我住在哪",
        "我住在哪里",
        "知道我在哪",
        "知道我在哪里",
        "自动定位"
    ).any(text::contains)

    private companion object {
        val WEATHER_TERMS = listOf(
            "天气", "穿什么", "穿啥", "怎么穿", "带伞", "雨伞", "防晒",
            "冷不冷", "热不热", "会不会下雨", "适合出门"
        )
        val MUTATION_TERMS = listOf(
            "添加", "新增", "创建", "建立", "加上", "删除", "删掉", "移除",
            "标记", "改成", "设置", "设为", "确认删除"
        )
        val REVIEW_TERMS = listOf("写评价", "帮我评价", "评分给", "评五星", "打五星")
        val STATUS_TERMS = listOf("用完", "没用完", "未用完", "丢弃", "废弃")
        val APP_DOMAIN_TERMS = listOf(
            "场景", "位置", "存放位置", "大位置", "子位置", "分类", "类别", "种类",
            "物品", "库存", "库房"
        )
        val COMMAND_PREFIXES = listOf("帮我", "请", "把", "删除", "删掉", "添加", "新增", "创建")
    }
}
