package com.youshu.app.data.agent

internal object AgentIntentPatterns {
    private val advicePhrases = listOf("放哪里", "放哪儿", "放哪", "应该放", "适合放")
    private val directMovePhrases = listOf(
        "放到", "放进", "存到", "移到", "移动到", "挪到", "换到",
        "放回", "存回", "移回", "移动回", "挪回", "换回", "改回",
        "位置改到", "位置改为", "位置换成", "存放位置改"
    )

    fun isItemLocationMove(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "")
        if (advicePhrases.any(compact::contains)) return false
        val directMove = directMovePhrases.any(compact::contains)
        val changesLocation = listOf("修改", "更改", "调整", "改").any(compact::contains) &&
            listOf("位置", "存放地点", "存放地方").any(compact::contains)
        return directMove || changesLocation
    }
}
