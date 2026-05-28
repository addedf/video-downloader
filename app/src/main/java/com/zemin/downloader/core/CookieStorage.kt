// auth/CookieStorage.kt
package com.zemin.downloader.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.zemin.downloader.appContext

object CookieStorage {
    private const val SP_FILE_NAME = "douyin_cookies"
    private const val KEY_COOKIE = "cookie_string"

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(
            SP_FILE_NAME, Context.MODE_PRIVATE
        )

    fun saveCookies(cookieString: String) {
        prefs.edit { putString(KEY_COOKIE, cookieString) }
    }

    fun getCookieString(): String? {
        return prefs.getString(KEY_COOKIE, null)
    }

    fun getCookiesMap(): Map<String, String> {
        val cookieString = getCookieString() ?: return emptyMap()
        return parseCookieString(cookieString)
    }

    fun clear() {
        prefs.edit { remove(KEY_COOKIE) }
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
