package com.zemin.downloader.common.core

import com.zemin.downloader.common.ApiMetric
import com.zemin.downloader.common.DownloadMetric
import com.zemin.downloader.impl.dy.DyDownloadResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * @author maozemin@coocaa.com
 * @desc
 */
object DownloadResultParser {

    fun fromJson(raw: String): DyDownloadResult {
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
        return DyDownloadResult(
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