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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
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

        private val USER_AGENT_POOL = arrayOf(DEFAULT_USER_AGENT)
        private val DETAIL_AIDS = listOf("6383", "1128")
        private val EMPTY_BODY_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L)
        private const val MS_TOKEN_RANDOM_PART_LENGTH = 182
        private const val MS_TOKEN_SUFFIX = "=="
        private const val MS_TOKEN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

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

    private val userAgent: String = USER_AGENT_POOL.first()
    private val cookieStore = ConcurrentHashMap<String, String>().apply {
        putAll(cookies)
    }
    private val secureRandom = SecureRandom()
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
                .header("Accept", "*/*")
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

            Log.w(TAG, "requestAwemeDetail failed: all aid/retry attempts returned empty body, trying web page fallback")
            requestAwemeDetailFromWebPage(awemeId)
        } catch (e: IOException) {
            Log.e(TAG, "requestAwemeDetail: IOException = ${e.message}", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "requestAwemeDetail: IllegalStateException = ${e.message}", e)
            null
        }
    }

    private fun requestAwemeDetailFromWebPage(awemeId: String): String? {
        val pageUrl = "$BASE_URL/video/$awemeId"
        val request = Request.Builder()
            .url(pageUrl)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                if (!response.isSuccessful || html.isBlank()) {
                    Log.w(
                        TAG,
                        "web page fallback failed: code=${response.code}, body=${html.preview()}"
                    )
                    return@runCatching null
                }
                Log.d(TAG, "web page fallback html preview: ${html.preview()}")
                Log.d(
                    TAG,
                    "web page fallback markers: universal=${html.contains("__UNIVERSAL_DATA_FOR_REHYDRATION__")}, " +
                        "render=${html.contains("RENDER_DATA")}, initial=${html.contains("__INITIAL_STATE__")}, " +
                        "pace=${html.contains("__pace_f")}, aweme=${html.contains(awemeId)}"
                )

                extractAwemeDetailFromHtml(html, awemeId)?.let { detail ->
                    Log.d(TAG, "web page fallback success: awemeId=$awemeId")
                    JSONObject().put("aweme_detail", detail).toString()
                } ?: run {
                    Log.w(TAG, "web page fallback failed: aweme detail not found in html")
                    null
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "web page fallback exception: ${error.message}", error)
        }.getOrNull()
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
                val body = response.body?.string().orEmpty()
                extractMsToken(body)?.let { token ->
                    cookieStore["msToken"] = token
                }
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
        val msToken = ensureMsToken()
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
            .addQueryParameter("cpu_core_num", "16")
            .addQueryParameter("device_memory", "8")
            .addQueryParameter("platform", "PC")
            .addQueryParameter("downlink", "10")
            .addQueryParameter("effective_type", "4g")
            .addQueryParameter("round_trip_time", "200")
            .addQueryParameter("support_h265", "1")
            .addQueryParameter("support_dash", "1")
            .addQueryParameter("uifid", "")
            .addQueryParameter("msToken", msToken)
            .build()
    }

    private fun extractAwemeDetailFromHtml(html: String, awemeId: String): JSONObject? {
        extractScriptJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.let { json ->
            findAwemeDetail(json, awemeId)?.let {
                Log.d(TAG, "web page fallback parser hit: __UNIVERSAL_DATA_FOR_REHYDRATION__")
                return it
            }
        }

        extractScriptJson(html, "RENDER_DATA")?.let { json ->
            findAwemeDetail(json, awemeId)?.let {
                Log.d(TAG, "web page fallback parser hit: RENDER_DATA json")
                return it
            }
        }

        extractScriptText(html, "RENDER_DATA")?.let { raw ->
            extractAwemeDetailFromEncodedText(raw, awemeId)?.let {
                Log.d(TAG, "web page fallback parser hit: RENDER_DATA encoded text")
                return it
            }
        }

        extractInlineJson(html)?.let { json ->
            findAwemeDetail(json, awemeId)?.let {
                Log.d(TAG, "web page fallback parser hit: inline json")
                return it
            }
        }

        logWebPageFallbackDiagnostics(html, awemeId)
        return null
    }

    private fun extractScriptJson(html: String, scriptId: String): Any? {
        val pattern = Regex(
            """<script[^>]+id=["']$scriptId["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val raw = pattern.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null

        return parseEncodedJsonOrNull(raw)
            ?: run {
                Log.d(TAG, "extractScriptJson parse failed: id=$scriptId, raw=${raw.preview()}")
                null
            }
    }

    private fun extractScriptText(html: String, scriptId: String): String? {
        val pattern = Regex(
            """<script[^>]+id=["']$scriptId["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractInlineJson(html: String): Any? {
        extractAssignedJson(html, "window.__INITIAL_STATE__")?.let { raw ->
            parseJsonOrNull(raw)?.let { return it }
        }

        val pacePattern = Regex(
            """self\.__pace_f\.push\(\[1,\s*"((?:\\.|[^"\\])*)"\]\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        pacePattern.findAll(html).forEach { match ->
            val decoded = unescapeJsString(match.groupValues[1])
            parseJsonOrNull(decoded)?.let { return it }
            extractFirstJsonObject(decoded)?.let { raw ->
                parseJsonOrNull(raw)?.let { return it }
            }
        }

        return null
    }

    private fun extractAssignedJson(html: String, assignmentName: String): String? {
        val assignmentIndex = html.indexOf(assignmentName)
        if (assignmentIndex < 0) return null

        val equalsIndex = html.indexOf('=', assignmentIndex)
        if (equalsIndex < 0) return null

        val objectStartIndex = html.indexOf('{', equalsIndex)
        if (objectStartIndex < 0) return null

        return extractBalancedJsonObject(html, objectStartIndex)
    }

    private fun extractFirstJsonObject(text: String): String? {
        val objectStartIndex = text.indexOf('{')
        if (objectStartIndex < 0) return null
        return extractBalancedJsonObject(text, objectStartIndex)
    }

    private fun extractBalancedJsonObject(text: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var quoteChar = '"'
        var escaped = false

        for (index in startIndex until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quoteChar -> inString = false
                }
                continue
            }

            when (char) {
                '"', '\'' -> {
                    inString = true
                    quoteChar = char
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun parseJsonOrNull(raw: String): Any? {
        val text = raw.trim()
        if (text.isBlank()) return null
        return runCatching {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseEncodedJsonOrNull(raw: String): Any? {
        normalizedJsonCandidates(raw).forEach { candidate ->
            parseJsonOrNull(candidate)?.let { return it }
        }
        return null
    }

    private fun normalizedJsonCandidates(raw: String): List<String> {
        val candidates = linkedSetOf<String>()
        fun add(value: String?) {
            val text = value?.trim().orEmpty()
            if (text.isNotBlank()) candidates.add(text)
        }

        add(raw)
        add(htmlEntityDecode(raw))
        add(urlDecode(raw))
        add(urlDecode(htmlEntityDecode(raw)))
        add(unescapeJsString(raw))

        candidates.toList().forEach { candidate ->
            val decoded = repeatedUrlDecode(candidate)
            add(decoded)
            add(unescapeJsString(decoded))
            stripJsonStringWrapper(candidate)?.let { add(it) }
        }

        return candidates.toList()
    }

    private fun stripJsonStringWrapper(value: String): String? {
        val text = value.trim()
        if (text.length < 2) return null
        val first = text.first()
        val last = text.last()
        if ((first != '"' && first != '\'') || first != last) return null
        return unescapeJsString(text.substring(1, text.lastIndex))
    }

    private fun extractAwemeDetailFromEncodedText(raw: String, awemeId: String): JSONObject? {
        normalizedJsonCandidates(raw).forEach { candidate ->
            findAwemeDetail(candidate, awemeId)?.let { return it }
            extractJSONObjectNearAwemeId(candidate, awemeId)?.let { return it }
            extractVideoUrlFromText(candidate)?.let { videoUrl ->
                Log.d(TAG, "web page fallback parser hit: direct video url")
                return buildMinimalAwemeDetail(awemeId, videoUrl)
            }
        }
        return null
    }

    private fun extractJSONObjectNearAwemeId(text: String, awemeId: String): JSONObject? {
        var searchIndex = text.indexOf(awemeId)
        while (searchIndex >= 0) {
            var objectStart = text.lastIndexOf('{', searchIndex)
            var attempts = 0
            while (objectStart >= 0 && attempts < 200) {
                extractBalancedJsonObject(text, objectStart)?.let { rawObject ->
                    parseJsonOrNull(rawObject)?.let { parsed ->
                        findAwemeDetail(parsed, awemeId)?.let { return it }
                    }
                }
                attempts++
                objectStart = text.lastIndexOf('{', objectStart - 1)
            }
            searchIndex = text.indexOf(awemeId, searchIndex + awemeId.length)
        }
        return null
    }

    private fun findAwemeDetail(node: Any?, awemeId: String): JSONObject? {
        return when (node) {
            is JSONObject -> {
                findKnownAwemeContainer(node, awemeId)?.let { return it }

                val objectAwemeId = node.firstString(
                    "aweme_id",
                    "awemeId",
                    "item_id",
                    "itemId",
                    "group_id",
                    "groupId",
                    "id"
                )
                val hasVideo = node.optJSONObject("video") != null ||
                    node.optJSONObject("video_info") != null ||
                    node.optJSONObject("videoInfo") != null
                if (objectAwemeId == awemeId && hasVideo) {
                    return normalizeAwemeDetail(node, awemeId)
                }

                if (node.containsAwemeIdDeep(awemeId)) {
                    findFirstVideoObject(node)?.let { video ->
                        return buildAwemeDetailFromVideo(awemeId, node, video)
                    }
                    findFirstVideoUrl(node)?.let { videoUrl ->
                        Log.d(TAG, "web page fallback parser hit: aweme container video url")
                        return buildMinimalAwemeDetail(awemeId, videoUrl)
                    }
                }

                node.optJSONObject("aweme_detail")?.let { detail ->
                    if (detail.matchesAwemeId(awemeId) || detail.optJSONObject("video") != null) {
                        return normalizeAwemeDetail(detail, awemeId)
                    }
                }

                node.keys().asSequence().firstNotNullOfOrNull { key ->
                    findAwemeDetail(node.opt(key), awemeId)
                }
            }
            is JSONArray -> {
                (0 until node.length()).asSequence().firstNotNullOfOrNull { index ->
                    findAwemeDetail(node.opt(index), awemeId)
                }
            }
            is String -> {
                if (!node.contains(awemeId)) {
                    null
                } else {
                    parseEncodedJsonOrNull(node)?.let { parsed ->
                        findAwemeDetail(parsed, awemeId)?.let { return it }
                    }
                    extractJSONObjectNearAwemeId(node, awemeId)
                }
            }
            else -> null
        }
    }

    private fun findKnownAwemeContainer(node: JSONObject, awemeId: String): JSONObject? {
        val keys = arrayOf(
            "aweme_detail",
            "awemeDetail",
            "aweme_info",
            "awemeInfo",
            "aweme",
            "itemStruct",
            "item",
            "detail",
            "videoDetail",
            "videoData",
            "post"
        )
        keys.forEach { key ->
            node.optJSONObject(key)?.let { child ->
                if (child.matchesAwemeId(awemeId) || child.containsAwemeIdDeep(awemeId)) {
                    findFirstVideoObject(child)?.let { video ->
                        return buildAwemeDetailFromVideo(awemeId, child, video)
                    }
                    findFirstVideoUrl(child)?.let { videoUrl ->
                        Log.d(TAG, "web page fallback parser hit: known container url key=$key")
                        return buildMinimalAwemeDetail(awemeId, videoUrl)
                    }
                    if (child.optJSONObject("video") != null ||
                        child.optJSONObject("video_info") != null ||
                        child.optJSONObject("videoInfo") != null
                    ) {
                        return normalizeAwemeDetail(child, awemeId)
                    }
                }
            }
        }
        return null
    }

    private fun buildAwemeDetailFromVideo(
        awemeId: String,
        container: JSONObject,
        rawVideo: JSONObject
    ): JSONObject {
        val detail = JSONObject().put("aweme_id", awemeId)
        detail.put("video", normalizeVideo(rawVideo))
        container.firstString("desc", "description", "title", "caption").takeIf { it.isNotBlank() }?.let {
            detail.put("desc", it)
        }
        container.firstJSONObject("author", "authorInfo", "authorUser", "user")?.let { detail.put("author", it) }
        Log.d(TAG, "web page fallback parser hit: aweme container video object")
        return detail
    }

    private fun urlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun repeatedUrlDecode(value: String): String {
        var decoded = value
        repeat(3) {
            val next = urlDecode(decoded)
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun htmlEntityDecode(value: String): String {
        return value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun unescapeJsString(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "")
            .replace("\\t", "")
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
    }

    private fun normalizeAwemeDetail(raw: JSONObject, awemeId: String): JSONObject {
        val detail = JSONObject(raw.toString())
        if (detail.optString("aweme_id").isBlank()) {
            detail.put("aweme_id", detail.firstString("awemeId", "item_id", "itemId", "group_id", "groupId", "id").ifBlank { awemeId })
        }

        if (detail.optString("desc").isBlank()) {
            detail.firstString("desc", "description", "title", "caption").takeIf { it.isNotBlank() }?.let {
                detail.put("desc", it)
            }
        }

        detail.firstJSONObject("author", "authorInfo", "authorUser", "user")?.let { author ->
            val normalizedAuthor = JSONObject(author.toString())
            if (normalizedAuthor.optString("nickname").isBlank()) {
                normalizedAuthor.firstString("nickname", "nickName", "name").takeIf { it.isNotBlank() }?.let {
                    normalizedAuthor.put("nickname", it)
                }
            }
            if (normalizedAuthor.optString("sec_uid").isBlank()) {
                normalizedAuthor.firstString("sec_uid", "secUid", "uid", "id").takeIf { it.isNotBlank() }?.let {
                    normalizedAuthor.put("sec_uid", it)
                }
            }
            detail.put("author", normalizedAuthor)
        }

        detail.firstJSONObject("video", "video_info", "videoInfo")?.let { video ->
            detail.put("video", normalizeVideo(video))
        }

        return detail
    }

    private fun normalizeVideo(raw: JSONObject): JSONObject {
        val video = JSONObject(raw.toString())

        video.firstJSONObject("play_addr", "playAddr", "play_addr_265", "playAddr265", "download_addr", "downloadAddr")?.let { playAddr ->
            video.put("play_addr", normalizeAddress(playAddr))
        }

        if (video.optJSONArray("bit_rate") == null) {
            video.firstJSONArray("bit_rate", "bitRate", "bit_rate_list", "bitRateList")?.let { bitRate ->
                val normalizedBitRate = JSONArray()
                for (index in 0 until bitRate.length()) {
                    val item = bitRate.optJSONObject(index) ?: continue
                    val normalizedItem = JSONObject(item.toString())
                    normalizedItem.firstJSONObject("play_addr", "playAddr", "play_addr_265", "playAddr265")?.let { playAddr ->
                        normalizedItem.put("play_addr", normalizeAddress(playAddr))
                    }
                    if (!normalizedItem.has("bit_rate")) {
                        normalizedItem.firstString("bit_rate", "bitRate", "qualityBitrate").toIntOrNull()?.let {
                            normalizedItem.put("bit_rate", it)
                        }
                    }
                    normalizedBitRate.put(normalizedItem)
                }
                video.put("bit_rate", normalizedBitRate)
            }
        }

        if (video.optLong("duration", 0L) == 0L) {
            video.firstString("duration", "durationMs", "duration_ms").toLongOrNull()?.let {
                video.put("duration", it)
            }
        }

        video.firstJSONObject("cover", "originCover", "dynamicCover", "animatedCover")?.let { cover ->
            video.put("cover", normalizeAddress(cover))
        }

        if (video.optJSONObject("play_addr") == null) {
            findFirstVideoUrl(video)?.let { url ->
                video.put("play_addr", JSONObject().put("url_list", JSONArray().put(url)))
            }
        }

        return video
    }

    private fun normalizeAddress(raw: JSONObject): JSONObject {
        val address = JSONObject(raw.toString())
        if (address.optJSONArray("url_list") == null) {
            address.firstJSONArray("url_list", "urlList", "urls")?.let { address.put("url_list", it) }
        }

        if (address.optJSONArray("url_list") == null) {
            address.firstString("url", "src", "mainUrl", "backupUrl").takeIf { it.isNotBlank() }?.let {
                address.put("url_list", JSONArray().put(normalizeVideoUrl(it)))
            }
        }

        return address
    }

    private fun extractVideoUrlFromText(text: String): String? {
        val urlPattern = Regex("""(?:https?:)?//[^"'<>\\\s]+""")
        return urlPattern.findAll(repeatedUrlDecode(unescapeJsString(text)))
            .map { normalizeVideoUrl(it.value) }
            .firstOrNull { isVideoCandidateUrl(it) }
    }

    private fun findFirstVideoUrl(node: Any?): String? {
        return when (node) {
            is JSONObject -> node.keys().asSequence().firstNotNullOfOrNull { key ->
                findFirstVideoUrl(node.opt(key))
            }
            is JSONArray -> (0 until node.length()).asSequence().firstNotNullOfOrNull { index ->
                findFirstVideoUrl(node.opt(index))
            }
            is String -> {
                val text = unescapeJsString(node)
                if (isVideoCandidateUrl(text)) normalizeVideoUrl(text) else extractVideoUrlFromText(text)
            }
            else -> null
        }
    }

    private fun findFirstVideoObject(node: Any?): JSONObject? {
        return when (node) {
            is JSONObject -> {
                node.firstJSONObject(
                    "video",
                    "video_info",
                    "videoInfo",
                    "video_data",
                    "videoData",
                    "video_play_info",
                    "videoPlayInfo"
                )?.takeIf { findFirstVideoUrl(it) != null }
                    ?: node.keys().asSequence().firstNotNullOfOrNull { key ->
                        findFirstVideoObject(node.opt(key))
                    }
            }
            is JSONArray -> (0 until node.length()).asSequence().firstNotNullOfOrNull { index ->
                findFirstVideoObject(node.opt(index))
            }
            is String -> parseEncodedJsonOrNull(node)?.let { findFirstVideoObject(it) }
            else -> null
        }
    }

    private fun isVideoCandidateUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("douyinvod.com/") ||
            normalized.contains("/aweme/v1/play/") ||
            normalized.contains("/aweme/v1/playwm/")
    }

    private fun normalizeVideoUrl(url: String): String {
        val withScheme = if (url.startsWith("//")) "https:$url" else url
        return withScheme
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("/playwm/", "/play/")
    }

    private fun buildMinimalAwemeDetail(awemeId: String, videoUrl: String): JSONObject {
        return JSONObject()
            .put("aweme_id", awemeId)
            .put("desc", "")
            .put("author", JSONObject())
            .put(
                "video",
                JSONObject()
                    .put("play_addr", JSONObject().put("url_list", JSONArray().put(videoUrl)))
                    .put(
                        "bit_rate",
                        JSONArray().put(
                            JSONObject()
                                .put("bit_rate", 0)
                                .put("is_watermark", 0)
                                .put("play_addr", JSONObject().put("url_list", JSONArray().put(videoUrl)))
                        )
                    )
            )
    }

    private fun JSONObject.matchesAwemeId(awemeId: String): Boolean {
        return firstString("aweme_id", "awemeId", "item_id", "itemId", "group_id", "groupId", "id") == awemeId
    }

    private fun Any?.containsAwemeIdDeep(awemeId: String): Boolean {
        return when (this) {
            is JSONObject -> {
                if (matchesAwemeId(awemeId)) return true
                keys().asSequence().any { key ->
                    val value = opt(key)
                    when {
                        value is String && value.contains(awemeId) -> true
                        value is JSONObject || value is JSONArray -> value.containsAwemeIdDeep(awemeId)
                        else -> false
                    }
                }
            }
            is JSONArray -> (0 until length()).any { index -> opt(index).containsAwemeIdDeep(awemeId) }
            is String -> contains(awemeId)
            else -> false
        }
    }

    private fun logWebPageFallbackDiagnostics(html: String, awemeId: String) {
        val renderData = extractScriptText(html, "RENDER_DATA")
        val renderCandidates = renderData?.let { normalizedJsonCandidates(it) }.orEmpty()
        val renderCandidate = renderCandidates.firstOrNull { it.contains(awemeId) }
            ?: renderCandidates.maxByOrNull { it.length }
        val renderJson = renderCandidate?.let { parseJsonOrNull(it) }
        val renderKeys = (renderJson as? JSONObject)?.keys()?.asSequence()?.take(12)?.joinToString(",").orEmpty()
        val awemeContext = renderCandidate?.contextAround(awemeId).orEmpty()
        val directVideoUrl = renderCandidates.firstNotNullOfOrNull { extractVideoUrlFromText(it) }
        Log.d(
            TAG,
            "web page fallback diagnostics: renderRawLen=${renderData?.length ?: 0}, " +
                "renderJson=${renderJson != null}, renderKeys=$renderKeys, " +
                "directVideo=${directVideoUrl != null}, awemeContext=${awemeContext.preview(500)}"
        )
    }

    private fun String.contextAround(needle: String, radius: Int = 240): String {
        val index = indexOf(needle)
        if (index < 0) return ""
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + needle.length + radius).coerceAtMost(length)
        return substring(start, end)
    }

    private fun JSONObject.firstJSONObject(vararg keys: String): JSONObject? {
        keys.forEach { key ->
            optJSONObject(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.firstJSONArray(vararg keys: String): JSONArray? {
        keys.forEach { key ->
            optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun ensureMsToken(): String {
        val existing = cookieStore["msToken"]?.trim().orEmpty()
        if (existing.isNotBlank()) return existing

        val fallback = generateFallbackMsToken()
        cookieStore["msToken"] = fallback
        Log.d(TAG, "ensureMsToken: generated fallback msToken length=${fallback.length}")
        return fallback
    }

    private fun generateFallbackMsToken(): String {
        return buildString(MS_TOKEN_RANDOM_PART_LENGTH + MS_TOKEN_SUFFIX.length) {
            repeat(MS_TOKEN_RANDOM_PART_LENGTH) {
                append(MS_TOKEN_ALPHABET[secureRandom.nextInt(MS_TOKEN_ALPHABET.length)])
            }
            append(MS_TOKEN_SUFFIX)
        }
    }

    private fun extractMsToken(html: String): String? {
        val patterns = listOf(
            Regex(""""msToken"\s*:\s*"([^"]+)""""),
            Regex("""msToken=([^"&\s]+)"""),
            Regex("""msToken%3D([^"%&\s]+)""")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        }
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
