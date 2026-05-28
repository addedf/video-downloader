package com.zemin.downloader.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zemin.downloader.DouyinDownloaderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PythonDownloadBridge {
    private const val PY_FILE_NAME_DOU_YIN = "android_entry"

    private val context: Context
        get() = DouyinDownloaderApp.appContext

    private val python: Python
        get() {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            return Python.getInstance()
        }

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val appDir = StorageManager.getAppFileDir().absolutePath
        val outDir = StorageManager.getPythonDownloadDir()
        val cookieString = CookieStorage.getCookieString()

        python.getModule(PY_FILE_NAME_DOU_YIN).callAttr("warm_up", appDir, outDir, cookieString)
    }

    suspend fun refreshCookies(cookieString: String) = withContext(Dispatchers.IO) {
        python.getModule(PY_FILE_NAME_DOU_YIN).callAttr("refresh_cookies", cookieString)
    }

    suspend fun download(inputText: String): PythonDownloadResult = withContext(Dispatchers.IO) {
        python.getModule(PY_FILE_NAME_DOU_YIN).callAttr("download", inputText).toString().let {
            PythonDownloadResult.fromJson(it)
        }
    }
}

data class PythonDownloadResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val outputDir: String?,
    val files: List<String>,
    val total: Int,
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
                            fileName = item.optString("file_name"),
                            ok = item.optBoolean("ok"),
                            status = item.optInt("status", 0),
                            bytes = item.optLong("bytes", 0L),
                            expectedBytes = item.optLong("expected_bytes", 0L),
                            durationMs = item.optInt("duration_ms", 0),
                            firstChunkMs = item.optInt("first_chunk_ms", 0),
                            speedKbps = item.optInt("speed_kbps", 0),
                            error = item.optString("error").takeIf { it.isNotBlank() })
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
                            ok = item.optBoolean("ok"),
                            durationMs = item.optInt("duration_ms", 0),
                            attempts = parseApiAttempts(item.optJSONArray("attempts"))
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
                total = json.optInt("total", 0),
                success = json.optInt("success", 0),
                failed = json.optInt("failed", 0),
                skipped = json.optInt("skipped", 0),
                timings = timings,
                downloadMetrics = downloadMetrics,
                apiMetrics = apiMetrics
            )
        }

        private fun parseApiAttempts(jsonArray: JSONArray?): List<ApiAttempt> {
            if (jsonArray == null) return emptyList()
            return mutableListOf<ApiAttempt>().apply {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    add(
                        ApiAttempt(
                            aid = item.optString("aid"),
                            ok = item.optBoolean("ok"),
                            durationMs = item.optInt("duration_ms", 0),
                            tokenMs = item.optInt("token_ms", 0),
                            signMs = item.optInt("sign_ms", 0),
                            httpMs = item.optInt("http_ms", 0),
                            status = item.optInt("status", 0),
                            error = item.optString("error").takeIf { it.isNotBlank() },
                            filterReason = item.optString("filter_reason")
                                .takeIf { it.isNotBlank() })
                    )
                }
            }
        }
    }
}

data class ApiMetric(
    val name: String, val ok: Boolean, val durationMs: Int, val attempts: List<ApiAttempt>
)

data class ApiAttempt(
    val aid: String,
    val ok: Boolean,
    val durationMs: Int,
    val tokenMs: Int,
    val signMs: Int,
    val httpMs: Int,
    val status: Int,
    val error: String?,
    val filterReason: String?
)

data class DownloadMetric(
    val fileName: String,
    val ok: Boolean,
    val status: Int,
    val bytes: Long,
    val expectedBytes: Long,
    val durationMs: Int,
    val firstChunkMs: Int,
    val speedKbps: Int,
    val error: String?
)
