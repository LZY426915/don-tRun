package com.youshu.app.ui.viewmodel

import com.youshu.app.data.agent.ChatHistoryService
import com.youshu.app.data.agent.ChatMessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMessageReducerTest {
    private val loading = ChatHistoryService.createAssistantMessage(
        content = "",
        now = 1L,
        status = ChatMessageStatus.LOADING
    )

    @Test
    fun appendAndReset_keepTheSameMessageIdentity() {
        val appended = StreamingMessageReducer.append(loading, "你好")
        val reset = StreamingMessageReducer.reset(appended)

        assertEquals(loading.id, appended.id)
        assertEquals("你好", appended.content)
        assertEquals(ChatMessageStatus.LOADING, appended.status)
        assertEquals(loading.id, reset.id)
        assertEquals("", reset.content)
    }

    @Test
    fun completeStopAndFail_haveDistinctFinalStates() {
        val partial = StreamingMessageReducer.append(loading, "已经生成一部分")

        assertEquals(ChatMessageStatus.NORMAL, StreamingMessageReducer.complete(partial).status)
        assertEquals(ChatMessageStatus.STOPPED, StreamingMessageReducer.stop(partial).status)
        assertEquals("已经生成一部分", StreamingMessageReducer.stop(partial).content)
        assertEquals(ChatMessageStatus.ERROR, StreamingMessageReducer.fail(partial, "服务繁忙").status)
        assertEquals("服务繁忙", StreamingMessageReducer.fail(partial, "服务繁忙").content)
    }

    @Test
    fun stopWithoutAnyText_usesAVisibleStoppedMessage() {
        assertEquals("已停止生成。", StreamingMessageReducer.stop(loading).content)
    }
}
