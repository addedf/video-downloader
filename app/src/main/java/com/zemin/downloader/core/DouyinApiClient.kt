package com.zemin.downloader.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

class DouyinApiClient(
    private val cookies: Map<String, String>,
    private val signatureProvider: SignatureProvider
) {
    companion object {
        private const val TAG = "DouyinApiClient"
        const val BASE_URL = "https://www.douyin.com"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private val USER_AGENT_POOL = arrayOf(
            DEFAULT_USER_AGENT,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        )
        private val DETAIL_AIDS = listOf("6383", "1128")
        private val EMPTY_BODY_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L)

        fun String?.preview(limit: Int = 800): String {
            if (this == null) return "null"
            val compact = replace(Regex("\\s+"), " ").trim()
            return if (compact.length <= limit) compact else compact.take(limit) + "...(truncated)"
        }

        fun maskCookie(cookie: String): String {
            return cookie.split(";").joinToString(";") { part ->
                val key = part.substringBefore("=", part).trim()
                if (key.isBlank()) "***" else "$key=***"
            }
        }
    }

    private val userAgent: String = USER_AGENT_POOL[Random().nextInt(USER_AGENT_POOL.size)]
    private val cookieStore = ConcurrentHashMap<String, String>().apply {
        putAll(cookies)
    }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore.mapNotNull { (name, value) ->
                    runCatching {
                        Cookie.Builder()
                            .name(name)
                            .value(value)
                            .domain(url.host)
                            .path("/")
                            .build()
                    }.getOrNull()
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookie ->
                    cookieStore[cookie.name] = cookie.value
                }
            }
        })
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Referer", "$BASE_URL/?recommend=1")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", "no-cache")
                .build()
            chain.proceed(request)
        }
        .addNetworkInterceptor(HttpLoggingInterceptor())
        .build()

    suspend fun requestAwemeDetail(awemeId: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "requestAwemeDetail: awemeId = $awemeId")
            warmUpCookiesIfNeeded()

            DETAIL_AIDS.forEach { aid ->
                EMPTY_BODY_RETRY_DELAYS_MS.forEachIndexed { attemptIndex, retryDelayMs ->
                    val result = requestAwemeDetailOnce(awemeId, aid, attemptIndex + 1)
                    when (result) {
                        is DetailResult.Success -> return@withContext result.body
                        is DetailResult.NonRetryableFailure -> return@withContext null
                        is DetailResult.EmptyBody -> {
                            Log.w(
                                TAG,
                                "requestAwemeDetail empty body: aid=$aid, attempt=${attemptIndex + 1}, retryDelayMs=$retryDelayMs"
                            )
                            delay(retryDelayMs)
                        }
                    }
                }
            }

            Log.w(TAG, "requestAwemeDetail failed: all aid/retry attempts returned empty body")
            null
        } catch (e: IOException) {
            Log.e(TAG, "requestAwemeDetail: IOException = ${e.message}", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "requestAwemeDetail: IllegalStateException = ${e.message}", e)
            null
        }
    }

    private suspend fun requestAwemeDetailOnce(
        awemeId: String,
        aid: String,
        attempt: Int
    ): DetailResult {
        val unsignedUrl = buildAwemeDetailUrl(awemeId, aid)
        val xBogus = signatureProvider.generateXBogus(unsignedUrl.toString(), userAgent)
        val signedUrl = unsignedUrl.newBuilder()
            .addQueryParameter("X-Bogus", xBogus)
            .build()

        val request = Request.Builder()
            .url(signedUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "requestAwemeDetail failed: aid=$aid, attempt=$attempt, code=${response.code}, message=${response.message}, body=${responseBody.preview()}"
                )
                return DetailResult.NonRetryableFailure
            }
            if (responseBody.isNullOrBlank()) {
                return DetailResult.EmptyBody
            }
            Log.d(TAG, "requestAwemeDetail success: aid=$aid, attempt=$attempt, body=${responseBody.preview()}")
            return DetailResult.Success(responseBody)
        }
    }

    private fun warmUpCookiesIfNeeded() {
        if (!cookieStore["msToken"].isNullOrBlank()) return

        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/?recommend=1")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.body?.close()
                Log.d(
                    TAG,
                    "warmUpCookiesIfNeeded: code=${response.code}, msTokenPresent=${!cookieStore["msToken"].isNullOrBlank()}"
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "warmUpCookiesIfNeeded failed: ${error.message}", error)
        }
    }

    private fun buildAwemeDetailUrl(awemeId: String, aid: String): HttpUrl {
        val msToken = cookieStore["msToken"] ?: generateMsToken()
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/detail/")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", aid)
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("aweme_id", awemeId)
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "290100")
            .addQueryParameter("version_name", "29.1.0")
            .addQueryParameter("update_version_code", "170400")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1536")
            .addQueryParameter("screen_height", "864")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "139.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "139.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("pc_libra_divert", "Windows")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "200")
            .addQueryParameter("support_h265", "1")
            .addQueryParameter("support_dash", "1")
            .addQueryParameter("uifid", cookieStore["UIFID"].orEmpty())
            .addQueryParameter("msToken", msToken)
            .build()
    }

    private fun generateMsToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..107).map { chars[Random().nextInt(chars.length)] }.joinToString("")
    }

    private class HttpLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startNs = System.nanoTime()

            Log.d(TAG, "--> ${request.method} ${request.url}")
            request.headers.forEach { header ->
                val value = if (header.first.equals("Cookie", ignoreCase = true)) {
                    maskCookie(header.second)
                } else {
                    header.second
                }
                Log.d(TAG, "--> ${header.first}: $value")
            }

            return try {
                val response = chain.proceed(request)
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                Log.d(TAG, "<-- ${response.code} ${response.message} (${tookMs}ms) ${response.request.url}")
                response.headers.forEach { header ->
                    val value = if (header.first.equals("Set-Cookie", ignoreCase = true)) {
                        maskCookie(header.second)
                    } else {
                        header.second
                    }
                    Log.d(TAG, "<-- ${header.first}: $value")
                }
                response
            } catch (e: IOException) {
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                Log.e(TAG, "<-- HTTP FAILED (${tookMs}ms): ${e.message}", e)
                throw e
            }
        }
    }

    private sealed class DetailResult {
        data class Success(val body: String) : DetailResult()
        object EmptyBody : DetailResult()
        object NonRetryableFailure : DetailResult()
    }
}
