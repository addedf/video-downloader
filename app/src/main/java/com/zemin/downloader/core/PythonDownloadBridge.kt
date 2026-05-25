package com.zemin.downloader.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PythonDownloadBridge(private val context: Context) {

    suspend fun warmUp() = withContext(Dispatchers.IO) {
        val appDataDir = File(context.filesDir, "python-runtime").apply { mkdirs() }
        val py = getPython()
        py.getModule("android_entry")
            .callAttr("warm_up", appDataDir.absolutePath)
    }

    suspend fun download(
        inputText: String,
        cookieHeader: String,
        outputDir: File
    ): PythonDownloadResult = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val appDataDir = File(context.filesDir, "python-runtime").apply { mkdirs() }
        val py = getPython()
        val raw = py.getModule("android_entry")
            .callAttr(
                "download",
                inputText,
                cookieHeader,
                outputDir.absolutePath,
                appDataDir.absolutePath
            )
            .toString()

        PythonDownloadResult.fromJson(raw)
    }

    private fun getPython(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }
}

data class PythonDownloadResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val outputDir: String,
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
                            error = item.optString("error").takeIf { it.isNotBlank() }
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
                            error = item.optString("error").takeIf { it.isNotBlank() },
                            filterReason = item.optString("filter_reason").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }
}

data class ApiMetric(
    val name: String,
    val ok: Boolean,
    val durationMs: Int,
    val attempts: List<ApiAttempt>
)

data class ApiAttempt(
    val aid: String,
    val ok: Boolean,
    val durationMs: Int,
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
