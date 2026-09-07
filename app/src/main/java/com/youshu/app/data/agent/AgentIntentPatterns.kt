package com.youshu.app.data.agent

internal object AgentIntentPatterns {
    fun isItemLocationMove(text: String): Boolean {
        return AgentLocationIntent.detect(text) != null
    }
}
