package com.zemin.downloader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.util.Random

class DouyinApiClient(
    private val cookies: Map<String, String>,
    private val signatureProvider: SignatureProvider
) {
    companion object {
        private val USER_AGENT_POOL = arrayOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        )
        const val BASE_URL = "https://www.douyin.com"
    }

    private val userAgent: String = USER_AGENT_POOL[Random().nextInt(USER_AGENT_POOL.size)]
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookies.map { (name, value) ->
                    Cookie.Builder().name(name).value(value).domain(url.host).build()
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                // 暂不处理服务器返回的 Cookie 更新
            }
        })
        .addInterceptor { chain ->
            val original = chain.request()
            val newRequest = original.newBuilder()
                .header("User-Agent", userAgent)
                .header("Referer", "$BASE_URL/?recommend=1")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            chain.proceed(newRequest)
        }
        .build()

    /**
     * 参照 Python api_client.py 的签名注入逻辑
     */
    suspend fun requestAwemeDetail(awemeId: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/aweme/v1/web/aweme/detail/?aweme_id=$awemeId"

        // 1. 生成 msToken
        val msToken = cookies["msToken"] ?: generateMsToken()

        // 2. 生成 X-Bogus
        val xBogus = signatureProvider.generateXBogus(url, userAgent)

        // 3. 构建请求
        val request = Request.Builder()
            .url(url)
            .header("X-Bogus", xBogus)
            .header("msToken", msToken) // 视接口需要
            .get()
            .build()

        // 4. 执行
        try {
            val response = client.newCall(request).execute()
            return@withContext response.body?.string()
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun generateMsToken(): String {
        // Python 项目中使用 MsTokenManager 从抖音页面 JavaScript 上下文中提取
        // 简化版：一个随机 107 位字符串
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..107).map { chars[Random().nextInt(chars.length)] }.joinToString("")
    }
}