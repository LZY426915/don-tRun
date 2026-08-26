package com.youshu.app.data.network

import android.content.Context
import com.youshu.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class BackendApiClient internal constructor(
    private val baseUrl: String,
    private val appVersion: String,
    private val sessionStore: BackendSessionStore,
    private val httpClient: OkHttpClient,
    private val nowMillis: () -> Long,
    private val requestIdFactory: () -> String
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        baseUrl = BuildConfig.YOUSHU_BACKEND_BASE_URL,
        appVersion = BuildConfig.VERSION_NAME,
        sessionStore = SharedPreferencesBackendSessionStore(context),
        httpClient = defaultHttpClient(),
        nowMillis = System::currentTimeMillis,
        requestIdFactory = { UUID.randomUUID().toString() }
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    private val normalizedBaseUrl = baseUrl.trim().trimEnd('/')

    suspend fun postJson(
        path: String,
        body: String,
        purpose: String? = null
    ): String = withContext(Dispatchers.IO) {
        validatePath(path)
        val firstSession = ensureSession()
        val firstResponse = execute(path, body, firstSession.token, purpose)
        if (firstResponse.statusCode != 401) {
            return@withContext firstResponse.requireSuccess()
        }

        sessionStore.clearSession()
        val refreshedSession = ensureSession()
        val retriedResponse = execute(path, body, refreshedSession.token, purpose)
        if (retriedResponse.statusCode == 401) {
            throw retriedResponse.toException(forceNotRetryable = true)
        }
        retriedResponse.requireSuccess()
    }

    suspend fun postJsonObject(
        path: String,
        body: JsonObject,
        purpose: String? = null
    ): JsonObject {
        val response = postJson(path, body.toString(), purpose)
        return runCatching { json.parseToJsonElement(response).jsonObject }
            .getOrElse {
                throw BackendApiException(
                    code = "INVALID_RESPONSE",
                    safeMessage = "服务返回的数据格式异常，请稍后重试。",
                    retryable = true,
                    cause = it
                )
            }
    }

    private suspend fun ensureSession(): BackendSession = sessionMutex.withLock {
        sessionStore.readSession()
            ?.takeIf { it.token.isNotBlank() && it.expiresAtMillis > nowMillis() + SESSION_EXPIRY_MARGIN_MS }
            ?: createSession().also { sessionStore.saveSession(it.token, it.expiresAtMillis) }
    }

    private fun createSession(): BackendSession {
        val requestBody = buildJsonObject {
            put("installationId", sessionStore.installationId())
            put("appVersion", appVersion)
        }.toString()
        val response = execute(
            path = SESSION_PATH,
            body = requestBody,
            token = null,
            purpose = null
        ).requireSuccess()
        val root = runCatching { json.parseToJsonElement(response).jsonObject }
            .getOrElse {
                throw BackendApiException(
                    code = "INVALID_RESPONSE",
                    safeMessage = "服务会话创建失败，请稍后重试。",
                    retryable = true,
                    cause = it
                )
            }
        val token = root["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawExpiresAt = root["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L
        if (token.isBlank() || rawExpiresAt <= 0L) {
            throw BackendApiException(
                code = "INVALID_RESPONSE",
                safeMessage = "服务会话创建失败，请稍后重试。",
                retryable = true
            )
        }
        val expiresAtMillis = if (rawExpiresAt < EPOCH_MILLIS_THRESHOLD) {
            rawExpiresAt * 1_000L
        } else {
            rawExpiresAt
        }
        return BackendSession(token, expiresAtMillis)
    }

    private fun execute(
        path: String,
        body: String,
        token: String?,
        purpose: String?
    ): BackendResponse {
        val request = Request.Builder()
            .url("$normalizedBaseUrl$path")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("X-Request-Id", requestIdFactory())
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
                purpose?.takeIf { it.isNotBlank() }?.let { header("X-YouShu-Purpose", it) }
            }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                BackendResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (error: SocketTimeoutException) {
            throw BackendApiException(
                code = "NETWORK_TIMEOUT",
                safeMessage = "网络请求超时，请稍后重试。",
                retryable = true,
                cause = error
            )
        } catch (error: UnknownHostException) {
            throw offlineException(error)
        } catch (error: ConnectException) {
            throw offlineException(error)
        } catch (error: IOException) {
            throw BackendApiException(
                code = "NETWORK_ERROR",
                safeMessage = "网络连接失败，请稍后重试。",
                retryable = true,
                cause = error
            )
        }
    }

    private fun BackendResponse.requireSuccess(): String {
        if (statusCode in 200..299) return body
        throw toException()
    }

    private fun BackendResponse.toException(forceNotRetryable: Boolean = false): BackendApiException {
        val errorObject = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val code = errorObject?.get("code")?.jsonPrimitive?.contentOrNull
            ?: defaultErrorCode(statusCode)
        val safeMessage = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: defaultErrorMessage(statusCode)
        val retryableStatus = statusCode in setOf(429, 502, 503, 504)
        val retryable = !forceNotRetryable && (
            retryableStatus ||
                errorObject?.get("retryable")?.jsonPrimitive?.booleanOrNull == true
            )
        val requestId = errorObject?.get("requestId")?.jsonPrimitive?.contentOrNull
        return BackendApiException(code, safeMessage, retryable, requestId)
    }

    private fun validatePath(path: String) {
        require(path.startsWith('/') && !path.startsWith("//") && "://" !in path) {
            "Backend path must be relative"
        }
        require(normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://")) {
            "Backend base URL is not configured"
        }
    }

    private fun offlineException(cause: Throwable) = BackendApiException(
        code = "NETWORK_OFFLINE",
        safeMessage = "当前网络不可用，请检查网络后重试。",
        retryable = true,
        cause = cause
    )

    private fun defaultErrorCode(statusCode: Int): String = when (statusCode) {
        400 -> "INVALID_REQUEST"
        401 -> "SESSION_EXPIRED"
        413 -> "PAYLOAD_TOO_LARGE"
        429 -> "RATE_LIMITED"
        502, 503 -> "PROVIDER_UNAVAILABLE"
        504 -> "PROVIDER_TIMEOUT"
        else -> "BACKEND_ERROR"
    }

    private fun defaultErrorMessage(statusCode: Int): String = when (statusCode) {
        400 -> "请求内容有误，请检查后重试。"
        401 -> "服务会话已过期，请重试。"
        413 -> "发送的内容太大，请缩小后重试。"
        429 -> "请求过于频繁，请稍后再试。"
        502, 503 -> "AI 服务暂时不可用，请稍后重试。"
        504 -> "AI 服务响应超时，请稍后重试。"
        else -> "服务暂时不可用，请稍后重试。"
    }

    private data class BackendResponse(
        val statusCode: Int,
        val body: String
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val SESSION_PATH = "/v1/session"
        const val SESSION_EXPIRY_MARGIN_MS = 30_000L
        const val EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

internal data class BackendSession(
    val token: String,
    val expiresAtMillis: Long
)

internal interface BackendSessionStore {
    fun installationId(): String
    fun readSession(): BackendSession?
    fun saveSession(token: String, expiresAtMillis: Long)
    fun clearSession()
}

private class SharedPreferencesBackendSessionStore(context: Context) : BackendSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun installationId(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLATION_ID, generated).apply()
        return generated
    }

    override fun readSession(): BackendSession? {
        val token = preferences.getString(KEY_SESSION_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = preferences.getLong(KEY_SESSION_EXPIRES_AT, 0L)
        return BackendSession(token, expiresAt)
    }

    override fun saveSession(token: String, expiresAtMillis: Long) {
        preferences.edit()
            .putString(KEY_SESSION_TOKEN, token)
            .putLong(KEY_SESSION_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    override fun clearSession() {
        preferences.edit()
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SESSION_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "backend_session"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_SESSION_TOKEN = "session_token"
        const val KEY_SESSION_EXPIRES_AT = "session_expires_at"
    }
}
