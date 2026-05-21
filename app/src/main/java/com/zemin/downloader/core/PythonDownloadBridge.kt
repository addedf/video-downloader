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

    suspend fun download(
        inputText: String,
        cookieHeader: String,
        outputDir: File
    ): PythonDownloadResult = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }

        outputDir.mkdirs()
        val appDataDir = File(context.filesDir, "python-runtime").apply { mkdirs() }
        val py = Python.getInstance()
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
    val skipped: Int
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
                skipped = json.optInt("skipped", 0)
            )
        }
    }
}
