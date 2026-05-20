package com.zemin.downloader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Random

class DouyinApiClient(
    private val cookies: Map<String, String>,
    private val signatureProvider: SignatureProvider
) {
    companion object {
        const val BASE_URL = "https://www.douyin.com"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private val USER_AGENT_POOL = arrayOf(
            DEFAULT_USER_AGENT,
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        )
    }

    private val userAgent: String = USER_AGENT_POOL[Random().nextInt(USER_AGENT_POOL.size)]
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookies.mapNotNull { (name, value) ->
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

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
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
        .build()

    suspend fun requestAwemeDetail(awemeId: String): String? = withContext(Dispatchers.IO) {
        try {
            val unsignedUrl = buildAwemeDetailUrl(awemeId)
            val xBogus = signatureProvider.generateXBogus(unsignedUrl.toString(), userAgent)
            val signedUrl = unsignedUrl.newBuilder()
                .addQueryParameter("X-Bogus", xBogus)
                .build()

            val request = Request.Builder()
                .url(signedUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            null
        }
    }

    private fun buildAwemeDetailUrl(awemeId: String): HttpUrl {
        val msToken = cookies["msToken"] ?: generateMsToken()
        return HttpUrl.Builder()
            .scheme("https")
            .host("www.douyin.com")
            .encodedPath("/aweme/v1/web/aweme/detail/")
            .addQueryParameter("device_platform", "webapp")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("channel", "channel_pc_web")
            .addQueryParameter("aweme_id", awemeId)
            .addQueryParameter("pc_client_type", "1")
            .addQueryParameter("version_code", "190500")
            .addQueryParameter("version_name", "19.5.0")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Chrome")
            .addQueryParameter("browser_version", "139.0.0.0")
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("engine_name", "Blink")
            .addQueryParameter("engine_version", "139.0.0.0")
            .addQueryParameter("os_name", "Windows")
            .addQueryParameter("os_version", "10")
            .addQueryParameter("cpu_core_num", "8")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "50")
            .addQueryParameter("msToken", msToken)
            .build()
    }

    private fun generateMsToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..107).map { chars[Random().nextInt(chars.length)] }.joinToString("")
    }
}
