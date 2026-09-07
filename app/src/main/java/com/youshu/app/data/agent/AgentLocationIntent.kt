package com.youshu.app.data.agent

internal data class LocationMoveCandidate(
    val normalizedText: String,
    val movementVerb: String
)

/**
 * Detects natural-language requests that are likely asking to move an inventory item.
 *
 * This is deliberately a candidate detector, not an executor: item and location
 * resolution remain the responsibility of InventoryAgentTool and the existing agent
 * tool-call flow.
 */
internal object AgentLocationIntent {
    private val advicePhrases = listOf(
        "放哪里", "放哪儿", "放哪", "应该放", "适合放", "怎么放", "如何放"
    )
    private val instructionalQuestionStarts = listOf("怎么", "如何", "为什么", "应该", "适合")
    private val directMovePhrases = listOf(
        "放到", "放进", "存到", "移到", "移动到", "挪到", "换到",
        "放回", "存回", "移回", "移动回", "挪回", "换回", "改回", "改到",
        "位置改到", "位置改为", "位置换成", "存放位置改"
    )
    private val movementVerbs = listOf(
        "整理", "放", "存", "移", "挪", "换", "改", "收", "摆", "归", "塞", "搁", "搬"
    )
    private val commandMarkers = listOf("把", "将", "帮我", "给我", "请", "麻烦", "替我", "帮忙")
    private val destinationCues = listOf(
        "到", "进", "回", "去", "里", "里面", "那边", "这边", "这里", "这儿", "旁边", "附近"
    )
    private val locationConcepts = listOf("位置", "地方", "地点", "房间", "区域", "场景")
    private val nonInventoryContentTerms = listOf("文字", "文本", "段落", "句子", "消息", "聊天记录")
    private val nonTargetComplements = setOf("一下", "一下子", "一会儿", "看看", "试试", "起来")
    private val movementTargetTail = Regex(
        "(?:整理|放|存|移|挪|换|改(?!成|为)|收|摆|归|塞|搁|搬)(?:到|进|回|去)?([\\u4e00-\\u9fa5]{2,16})$"
    )

    fun detect(rawText: String): LocationMoveCandidate? {
        val text = rawText.replace(Regex("\\s+"), "").trim()
        if (text.isBlank()) return null
        if (isAdviceOrInstructionQuestion(text)) return null
        if (nonInventoryContentTerms.any(text::contains)) return null

        directMovePhrases.firstOrNull(text::contains)?.let { phrase ->
            return LocationMoveCandidate(text, phrase)
        }

        val changesLocation = listOf("修改", "更改", "调整", "改").any(text::contains) &&
            locationConcepts.any(text::contains)
        if (changesLocation) {
            return LocationMoveCandidate(text, "修改位置")
        }

        val movementVerb = movementVerbs.firstOrNull(text::contains) ?: return null
        val hasCommandFrame = commandMarkers.any(text::contains)
        val hasDestinationCue = destinationCues.any(text::contains)
        val targetTail = movementTargetTail.find(text)?.groupValues?.getOrNull(1)
        val hasTargetTail = targetTail != null && targetTail !in nonTargetComplements
        val hasLocationConcept = locationConcepts.any(text::contains)

        if (!hasCommandFrame && !hasTargetTail) return null
        if (!hasDestinationCue && !hasTargetTail && !hasLocationConcept) return null

        return LocationMoveCandidate(text, movementVerb)
    }

    private fun isAdviceOrInstructionQuestion(text: String): Boolean {
        if (advicePhrases.any(text::contains)) return true
        return instructionalQuestionStarts.any(text::startsWith) ||
            listOf("怎么把", "如何把", "怎么给", "如何给").any(text::contains)
    }
}
