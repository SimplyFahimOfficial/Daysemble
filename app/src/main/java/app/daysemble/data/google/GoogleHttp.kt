package app.daysemble.data.google

import app.daysemble.data.google.auth.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Classified API failure; the sync engine decides retry/backoff from the type. */
sealed class ApiError(message: String) : Exception(message) {
    /** No connectivity / timeout. Retry later. */
    class Network(cause: IOException) : ApiError("network: ${cause.javaClass.simpleName}")
    /** 401 after one token refresh, or no token available without UI. */
    class Auth : ApiError("auth")
    class NotFound : ApiError("not_found")
    /** 410 Gone: sync token invalid (Calendar) or resource deleted. */
    class Gone : ApiError("gone")
    /** 409 Conflict (e.g. duplicate client-supplied id). */
    class Conflict : ApiError("conflict")
    /** 412 Precondition failed (etag mismatch). */
    class Precondition : ApiError("precondition")
    /** 403 rate/quota, 429, 5xx. Retry with backoff. */
    class RateLimited(val code: Int) : ApiError("rate_limited_$code")
    class Server(val code: Int) : ApiError("server_$code")
    /** Other 4xx: bad request or permission. Not retried. */
    class Client(val code: Int, val reason: String?) : ApiError("client_$code${reason?.let { ":$it" } ?: ""}")

    val isRetryable get() = this is Network || this is RateLimited || this is Server
}

/**
 * Minimal JSON-over-HTTPS client for Google REST APIs. Bodies (event/task content) are never logged.
 */
class GoogleHttp(
    private val tokens: TokenProvider,
    private val client: OkHttpClient = defaultClient(),
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    suspend fun get(url: HttpUrl): String = execute { Request.Builder().url(url).get() }

    suspend fun send(method: String, url: HttpUrl, body: String?, ifMatch: String? = null): String = execute {
        val rb: RequestBody? = body?.toRequestBody(JSON)
        Request.Builder().url(url).method(method, rb ?: if (method == "DELETE") null else "".toRequestBody(JSON))
            .apply { if (ifMatch != null) header("If-Match", ifMatch) }
    }

    private suspend fun execute(build: () -> Request.Builder): String = withContext(Dispatchers.IO) {
        var token = tokens.token() ?: throw ApiError.Auth()
        repeat(2) { attempt ->
            val req = build().header("Authorization", "Bearer $token").header("Accept", "application/json").build()
            val resp = try { client.newCall(req).execute() } catch (e: IOException) { throw ApiError.Network(e) }
            resp.use { r ->
                val text = r.body.string()
                when {
                    r.isSuccessful -> return@withContext text
                    r.code == 401 && attempt == 0 -> {
                        tokens.invalidate(token)
                        token = tokens.token() ?: throw ApiError.Auth()
                    }
                    else -> throw classify(r.code, text)
                }
            }
        }
        throw ApiError.Auth()
    }

    private fun classify(code: Int, body: String): ApiError {
        val reason = Regex("\"reason\"\\s*:\\s*\"([A-Za-z]+)\"").find(body)?.groupValues?.get(1)
        return when (code) {
            401 -> ApiError.Auth()
            404 -> ApiError.NotFound()
            409 -> ApiError.Conflict()
            410 -> ApiError.Gone()
            412 -> ApiError.Precondition()
            429 -> ApiError.RateLimited(code)
            // The token lacks a scope (a service the user didn't allow): that needs consent, not a retry.
            403 if body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT") -> ApiError.Auth()
            403 -> if (reason != null && (reason.contains("RateLimit", true) || reason.contains("quota", true))) ApiError.RateLimited(code) else ApiError.Client(code, reason)
            in 500..599 -> ApiError.Server(code)
            else -> ApiError.Client(code, reason)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun url(base: String): HttpUrl = base.toHttpUrl()
    }
}
