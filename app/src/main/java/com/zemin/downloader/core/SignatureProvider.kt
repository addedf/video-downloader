package com.zemin.downloader.core

import android.content.Context
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 签名提供器
 * 使用 QuickJS 引擎执行 assets 中的 signatures.js 签名逻辑
 */
class SignatureProvider(private val context: Context) {

    // 将 JS 文件内容缓存在内存中，避免重复读取
    private var jsLibrary: String? = null

    /**
     * 预加载 JS 库（建议在 App 启动时调用一次）
     */
    suspend fun preload(): SignatureProvider = withContext(Dispatchers.IO) {
        if (jsLibrary == null) {
            jsLibrary = context.assets.open("signatures.js").bufferedReader().use { it.readText() }
        }
        this@SignatureProvider
    }

    /**
     * 生成 X-Bogus 签名
     * @param urlOrQuery 请求的完整 URL 或者 query 部分（Python 里传给 XBogus 的字符串）
     * @param userAgent 当前 UA
     * @return X-Bogus 签名字符串
     */
    suspend fun generateXBogus(urlOrQuery: String, userAgent: String): String {
        val lib = jsLibrary ?: throw IllegalStateException("JS library not preloaded. Call preload() first.")
        return withContext(Dispatchers.Default) {
            quickJs {
                // 先加载 JS 库
                evaluate<Any?>(lib)

                // 调用 XBogus.sign(query, userAgent)
                val jsCall = "XBogus.sign(${JSONObject.quote(urlOrQuery)}, ${JSONObject.quote(userAgent)})"

                evaluate<String>(jsCall)
            }
        }
    }

    /**
     * 生成 A-Bogus 签名
     * @param params 请求参数键值对
     * @param userAgent 当前 UA
     * @param timestamp 毫秒级时间戳（通常由服务端下发或 Date.now()）
     * @return A-Bogus 签名字符串
     */
    suspend fun generateABogus(
        params: Map<String, String>,
        userAgent: String,
        timestamp: Long
    ): String {
        val lib = jsLibrary ?: throw IllegalStateException("JS library not preloaded. Call preload() first.")
        return withContext(Dispatchers.Default) {
            quickJs {
                evaluate<Any?>(lib)

                // 将 Map 转为 JS 对象字符串
                val paramsJson = params.entries.joinToString(", ") { (k, v) ->
                    "${JSONObject.quote(k)}: ${JSONObject.quote(v)}"
                }.let { "{$it}" }

                val jsCall = "ABogus.sign($paramsJson, ${JSONObject.quote(userAgent)}, $timestamp)"

                evaluate<String>(jsCall)
            }
        }
    }
}
