package com.zemin.downloader.update

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(
    private val context: Context,
    private val updateJsonUrl: String = UpdateConfig.UPDATE_JSON_URL,
) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = updateJsonUrl.trim()
        if (url.isEmpty()) return@withContext null

        val info = fetchUpdateInfo(url)
        return@withContext info.takeIf { it.canUpdate(currentVersionCode()) }
    }

    private fun fetchUpdateInfo(url: String): UpdateInfo {
        Log.d(TAG, "fetchUpdateInfo: url = $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = REQUEST_METHOD_GET
            connection.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = UpdateConfig.READ_TIMEOUT_MS
            connection.useCaches = false
            if (connection.responseCode != HTTP_OK) return UpdateInfo.EMPTY
            connection.inputStream.bufferedReader().use { reader ->
                parseUpdateInfo(reader.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUpdateInfo(jsonText: String): UpdateInfo {
        Log.d(TAG, "parseUpdateInfo: config = $jsonText")
        val json = JSONObject(jsonText)
        return UpdateInfo(
            versionCode = json.optLong(KEY_VERSION_CODE, INVALID_VERSION_CODE),
            versionName = json.optString(KEY_VERSION_NAME),
            apkUrl = json.optString(KEY_APK_URL),
            changelog = json.optString(KEY_CHANGELOG),
            forceUpdate = json.optBoolean(KEY_FORCE_UPDATE, false),
        )
    }

    private fun currentVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val REQUEST_METHOD_GET = "GET"
        private const val HTTP_OK = 200
        private const val INVALID_VERSION_CODE = 0L
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_APK_URL = "apkUrl"
        private const val KEY_CHANGELOG = "changelog"
        private const val KEY_FORCE_UPDATE = "forceUpdate"
    }
}
