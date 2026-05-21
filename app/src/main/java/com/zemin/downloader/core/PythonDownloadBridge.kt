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
    val timings: Map<String, Int>
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
                timings = timings
            )
        }
    }
}
