// auth/CookieStorage.kt
package com.zemin.downloader.core

import android.content.Context
import android.content.SharedPreferences
import com.zemin.downloader.DouyinDownloaderApp

object CookieStorage {

    private val prefs: SharedPreferences
        get() = DouyinDownloaderApp.appContext.getSharedPreferences(
            "douyin_cookies",
            Context.MODE_PRIVATE
        )

    fun saveCookies(cookieString: String) {
        prefs.edit().putString("cookie_string", cookieString).apply()
    }

    fun getCookieString(): String? {
        return prefs.getString("cookie_string", null)
    }

    fun getCookiesMap(): Map<String, String> {
        val cookieString = getCookieString() ?: return emptyMap()
        return parseCookieString(cookieString)
    }

    fun clear() {
        prefs.edit().remove("cookie_string").apply()
    }

    private fun parseCookieString(cookieString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cookieString.split(";").forEach { part ->
            val trimmed = part.trim()
            val idx = trimmed.indexOf("=")
            if (idx > 0) {
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }
}
