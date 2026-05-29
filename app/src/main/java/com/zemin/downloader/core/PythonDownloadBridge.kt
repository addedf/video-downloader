package com.zemin.downloader.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zemin.downloader.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PythonDownloadBridge {
    private const val PY_FILE_NAME_DOU_YIN = "dy.cli.dy_android_entry"
    private const val PY_FILE_NAME_XHS = "xhs.cli.xhs_android_entry"

    private val context: Context
        get() = appContext

    private val python: Python
        get() {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            return Python.getInstance()
        }

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val appDir = StorageManager.getAppFileDir().absolutePath
        val outDir = StorageManager.getPythonDownloadDir().absolutePath
        val cookieString = CookieStorage.getCookieString()

        python.getModule(PY_FILE_NAME_DOU_YIN)
            .callAttr("warm_up", appDir, outDir, cookieString)
        python.getModule(PY_FILE_NAME_XHS)
            .callAttr("warm_up", appDir, outDir, cookieString)
    }

    suspend fun refreshCookies(cookieString: String) = withContext(Dispatchers.IO) {
        python.getModule(PY_FILE_NAME_DOU_YIN).callAttr("refresh_cookies", cookieString)
        python.getModule(PY_FILE_NAME_XHS).callAttr("refresh_cookies", cookieString)
    }

    suspend fun download(inputText: String): PythonDownloadResult = withContext(Dispatchers.IO) {
        val moduleName = if (isXhsInput(inputText)) PY_FILE_NAME_XHS else PY_FILE_NAME_DOU_YIN
        python.getModule(moduleName).callAttr("download", inputText).toString().let {
            PythonDownloadResult.fromJson(it)
        }
    }

    private fun isXhsInput(inputText: String): Boolean {
        return inputText.contains("xiaohongshu.com", ignoreCase = true) ||
            inputText.contains("xhslink.com", ignoreCase = true)
    }
}

data class PythonDownloadResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val outputDir: String?,
    val files: List<String>,
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val timings: Map<String, Int>,
    val downloadMetrics: List<DownloadMetric>,
    val apiMetrics: List<ApiMetric>
) {
    companion object {
        fun fromJson(raw: String): PythonDownloadResult {
            val json = JSONObject(raw)
            val filesJson = json.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<String>().apply {
                for (index in 0 until filesJson.length()) {
                    val value = filesJson.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
            val timingsJson = json.optJSONObject("timings")
            val timings = linkedMapOf<String, Int>().apply {
                if (timingsJson != null) {
                    val keys = timingsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, timingsJson.optInt(key))
                    }
                }
            }
            val metricsJson = json.optJSONArray("download_metrics") ?: JSONArray()
            val downloadMetrics = mutableListOf<DownloadMetric>().apply {
                for (index in 0 until metricsJson.length()) {
                    val item = metricsJson.optJSONObject(index) ?: continue
                    add(
                        DownloadMetric(
                            ok = item.optBoolean("ok"),
                            host = item.optString("host").takeIf { it.isNotBlank() },
                            finalHost = item.optString("final_host").takeIf { it.isNotBlank() },
                            bytes = item.optLong("bytes", 0L),
                            durationMs = item.optInt("duration_ms", 0),
                            firstChunkMs = item.optInt("first_chunk_ms", 0),
                            speedKbps = item.optInt("speed_kbps", 0)
                        )
                    )
                }
            }
            val apiMetricsJson = json.optJSONArray("api_metrics") ?: JSONArray()
            val apiMetrics = mutableListOf<ApiMetric>().apply {
                for (index in 0 until apiMetricsJson.length()) {
                    val item = apiMetricsJson.optJSONObject(index) ?: continue
                    add(
                        ApiMetric(
                            name = item.optString("name"),
                            durationMs = item.optInt("duration_ms", 0)
                        )
                    )
                }
            }
            val error = json.optString("error").takeIf { it.isNotBlank() }
            return PythonDownloadResult(
                ok = json.optBoolean("ok", false),
                message = json.optString("message").ifBlank { error ?: "下载任务结束" },
                error = error,
                outputDir = json.optString("output_dir"),
                files = files,
                success = json.optInt("success", 0),
                failed = json.optInt("failed", 0),
                skipped = json.optInt("skipped", 0),
                timings = timings,
                downloadMetrics = downloadMetrics,
                apiMetrics = apiMetrics
            )
        }
    }
}

data class ApiMetric(
    val name: String,
    val durationMs: Int
)

data class DownloadMetric(
    val ok: Boolean,
    val host: String?,
    val finalHost: String?,
    val bytes: Long,
    val durationMs: Int,
    val firstChunkMs: Int,
    val speedKbps: Int
)
