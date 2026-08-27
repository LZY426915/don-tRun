package com.youshu.app.ui.viewmodel

import com.youshu.app.data.agent.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingConversationStateTest {
    private val first = conversation("first", "第一条", 1L)
    private val second = conversation("second", "第二条", 2L)
    private val updatedFirst = first.copy(title = "已更新", updatedAt = 3L)

    @Test
    fun replaceExisting_updatesAndSortsAnExistingConversation() {
        assertEquals(
            listOf(updatedFirst, second),
            StreamingConversationState.replaceExisting(listOf(first, second), updatedFirst)
        )
    }

    @Test
    fun replaceExisting_doesNotResurrectADeletedConversation() {
        assertEquals(
            listOf(second),
            StreamingConversationState.replaceExisting(listOf(second), updatedFirst)
        )
    }

    @Test
    fun updateActive_onlyTouchesTheConversationThatIsStillSelected() {
        assertEquals(second, StreamingConversationState.updateActive(second, updatedFirst))
        assertEquals(updatedFirst, StreamingConversationState.updateActive(first, updatedFirst))
    }

    private fun conversation(id: String, title: String, updatedAt: Long) = ChatConversation(
        id = id,
        title = title,
        createdAt = 0L,
        updatedAt = updatedAt
    )
}
