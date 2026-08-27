package com.youshu.app.data.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackendApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryBackendSessionStore
    private lateinit var client: BackendApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryBackendSessionStore()
        client = BackendApiClient(
            baseUrl = server.url("/").toString(),
            appVersion = "1.2.0",
            sessionStore = store,
            httpClient = OkHttpClient(),
            nowMillis = { 1_000L },
            requestIdFactory = { "request-id" }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postJson_createsSessionAndRetriesOnceAfter401() = runTest {
        server.enqueue(jsonResponse("""{"token":"token-1","expiresAt":9999999999999}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(expiredErrorJson))
        server.enqueue(jsonResponse("""{"token":"token-2","expiresAt":9999999999999}"""))
        server.enqueue(jsonResponse("""{"choices":[]}"""))

        val response = client.postJson("/v1/deepseek/chat/completions", "{}")

        assertEquals("""{"choices":[]}""", response)
        assertEquals("/v1/session", server.takeRequest().path)
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("/v1/session", server.takeRequest().path)
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun postJson_attachesRequestIdAndPurpose() = runTest {
        store.saveSession("cached-token", Long.MAX_VALUE)
        server.enqueue(jsonResponse("""{"choices":[]}"""))

        client.postJson("/v1/qwen/chat/completions", "{}", purpose = "vision")

        val request = server.takeRequest()
        assertEquals("Bearer cached-token", request.getHeader("Authorization"))
        assertEquals("request-id", request.getHeader("X-Request-Id"))
        assertEquals("vision", request.getHeader("X-YouShu-Purpose"))
    }

    @Test
    fun postJson_mapsStableBackendError() = runTest {
        store.saveSession("cached-token", Long.MAX_VALUE)
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody(
                    """{"error":{"code":"RATE_LIMITED","message":"请求过于频繁，请稍后再试。","requestId":"req-429","retryable":true}}"""
                )
        )

        val failure = runCatching {
            client.postJson("/v1/deepseek/chat/completions", "{}")
        }.exceptionOrNull() as BackendApiException

        assertEquals("RATE_LIMITED", failure.code)
        assertEquals("请求过于频繁，请稍后再试。", failure.safeMessage)
        assertEquals("req-429", failure.requestId)
        assertTrue(failure.retryable)
    }

    @Test
    fun postJson_doesNotRetrySecond401() = runTest {
        store.saveSession("cached-token", Long.MAX_VALUE)
        server.enqueue(MockResponse().setResponseCode(401).setBody(expiredErrorJson))
        server.enqueue(jsonResponse("""{"token":"new-token","expiresAt":9999999999999}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(expiredErrorJson))

        val failure = runCatching {
            client.postJson("/v1/deepseek/chat/completions", "{}")
        }.exceptionOrNull() as BackendApiException

        assertEquals("SESSION_EXPIRED", failure.code)
        assertFalse(failure.retryable)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun postSse_attachesSessionAndEmitsChunkedEvents() = runTest {
        store.saveSession("cached-token", Long.MAX_VALUE)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(
                    """
                    event: text-delta
                    data: {"text":"你"}

                    event: text-delta
                    data: {"text":"好"}

                    event: done
                    data: {"finishReason":"stop"}

                    """.trimIndent(),
                    7
                )
        )

        val events = client.postSse(
            "/v1/deepseek/chat/completions",
            """{"messages":[],"stream":true}"""
        ).toList()

        assertEquals(
            listOf(
                BackendStreamEvent.TextDelta("你"),
                BackendStreamEvent.TextDelta("好"),
                BackendStreamEvent.Done("stop")
            ),
            events
        )
        val request = server.takeRequest()
        assertEquals("Bearer cached-token", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun postSse_refreshesOneExpiredSessionBeforeReadingStream() = runTest {
        store.saveSession("expired-token", Long.MAX_VALUE)
        server.enqueue(MockResponse().setResponseCode(401).setBody(expiredErrorJson))
        server.enqueue(jsonResponse("""{"token":"new-token","expiresAt":9999999999999}"""))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: done\ndata: {\"finishReason\":\"stop\"}\n\n")
        )

        assertEquals(
            listOf(BackendStreamEvent.Done("stop")),
            client.postSse("/v1/deepseek/chat/completions", """{"messages":[],"stream":true}""")
                .toList()
        )
        assertEquals("Bearer expired-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/v1/session", server.takeRequest().path)
        assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test(timeout = 3_000)
    fun postSse_cancellationStopsWaitingForLaterChunks() = runBlocking {
        store.saveSession("cached-token", Long.MAX_VALUE)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "event: text-delta\ndata: {\"text\":\"先\"}\n\n" +
                        "event: text-delta\ndata: {\"text\":\"后\"}\n\n"
                )
                .throttleBody(8, 50, TimeUnit.MILLISECONDS)
        )

        val events = client.postSse("/v1/deepseek/chat/completions", """{"messages":[],"stream":true}""")
            .take(1)
            .toList()

        assertEquals(listOf(BackendStreamEvent.TextDelta("先")), events)
    }

    @Test(timeout = 3_000)
    fun postSse_externalCancellationInterruptsABlockedReadPromptly() = runBlocking {
        store.saveSession("cached-token", Long.MAX_VALUE)
        client = BackendApiClient(
            baseUrl = server.url("/").toString(),
            appVersion = "1.2.0",
            sessionStore = store,
            httpClient = OkHttpClient.Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
            nowMillis = { 1_000L },
            requestIdFactory = { "request-id" }
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBodyDelay(1, TimeUnit.SECONDS)
                .setBody("event: done\ndata: {\"finishReason\":\"stop\"}\n\n")
        )

        val job = launch {
            client.postSse("/v1/deepseek/chat/completions", """{"messages":[],"stream":true}""")
                .toList()
        }
        server.takeRequest(1, TimeUnit.SECONDS)

        val stoppedPromptly = withTimeoutOrNull(300) {
            job.cancelAndJoin()
            true
        } ?: false
        if (!stoppedPromptly) job.join()

        assertTrue("cancel should close the blocked HTTP call immediately", stoppedPromptly)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val expiredErrorJson =
        """{"error":{"code":"SESSION_EXPIRED","message":"会话已过期，请重试。","requestId":"expired","retryable":false}}"""
}

private class InMemoryBackendSessionStore : BackendSessionStore {
    private var session: BackendSession? = null

    override fun installationId(): String = "00000000-0000-4000-8000-000000000001"

    override fun readSession(): BackendSession? = session

    override fun saveSession(token: String, expiresAtMillis: Long) {
        session = BackendSession(token, expiresAtMillis)
    }

    override fun clearSession() {
        session = null
    }
}
