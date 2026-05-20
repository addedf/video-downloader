package com.zemin.downloader.parse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object UrlResolver {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun extractShortLink(text: String): String? {
        return Regex("""https?://v\.douyin\.com/[A-Za-z0-9/?=._%#&-]+""")
            .find(text)
            ?.value
            ?.trimEnd('.', ',', ';', '，', '。', '；')
    }

    suspend fun resolve(input: String): ResolveResult = withContext(Dispatchers.IO) {
        val normalizedInput = input.trim()
        if (normalizedInput.isBlank()) {
            return@withContext ResolveResult.Error("请先粘贴抖音分享文本或视频链接")
        }

        extractAwemeId(normalizedInput)?.let {
            return@withContext ResolveResult.Success(it)
        }

        val shortLink = extractShortLink(normalizedInput)
        if (shortLink == null) {
            if (looksLikeDouyinCommand(normalizedInput)) {
                return@withContext ResolveResult.Error(
                    "当前文本只有抖音口令，没有真实短链。请在抖音分享面板选择“复制链接”，粘贴包含 https://v.douyin.com/ 的完整文本。"
                )
            }

            return@withContext ResolveResult.Error(
                "没有找到可访问的抖音短链。请在抖音里点分享/复制链接，粘贴包含 https://v.douyin.com/ 的完整文本。"
            )
        }

        resolveShortLink(shortLink)
    }

    suspend fun resolveAwemeId(shortUrl: String): String? {
        return when (val result = resolve(shortUrl)) {
            is ResolveResult.Success -> result.awemeId
            is ResolveResult.Error -> null
        }
    }

    private fun looksLikeDouyinCommand(text: String): Boolean {
        return text.contains("复制打开抖音") || Regex("""[A-Za-z0-9]{2,}:/""").containsMatchIn(text)
    }

    private fun extractAwemeId(text: String): String? {
        Regex("""(?:/video/|aweme_id=)(\d{10,})""").find(text)?.let {
            return it.groupValues[1]
        }

        return Regex("""^\d{10,}$""").find(text)?.value
    }

    private fun resolveShortLink(shortUrl: String): ResolveResult {
        return try {
            var currentUrl = shortUrl
            repeat(5) {
                val request = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.douyin.com/")
                    .build()

                client.newCall(request).execute().use { response ->
                    val location = response.header("Location")
                    if (location.isNullOrBlank()) {
                        extractAwemeId(currentUrl)?.let { return ResolveResult.Success(it) }
                        return ResolveResult.Error("短链没有返回作品跳转地址")
                    }

                    currentUrl = response.request.url.resolve(location)?.toString() ?: location
                    extractAwemeId(currentUrl)?.let { return ResolveResult.Success(it) }
                }
            }

            ResolveResult.Error("短链跳转次数过多，仍未解析到视频 ID")
        } catch (e: Exception) {
            ResolveResult.Error("解析分享链接失败：${e.message ?: "网络异常"}")
        }
    }
}

sealed class ResolveResult {
    data class Success(val awemeId: String) : ResolveResult()
    data class Error(val message: String) : ResolveResult()
}
