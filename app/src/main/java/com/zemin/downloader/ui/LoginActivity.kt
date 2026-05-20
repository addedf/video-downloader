// ui/LoginActivity.kt
package com.zemin.downloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.zemin.downloader.core.CookieStorage

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var cookieStorage: CookieStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cookieStorage = CookieStorage(this)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 使用桌面版 UA 更容易触发完整 Cookie
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // 每次页面开始加载时检查 Cookie 是否已包含登录态
                    checkLoginStatus()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkLoginStatus()
                }
            }
        }
        setContentView(webView)

        // 清除旧 Cookie，确保全新登录
        CookieManager.getInstance().removeAllCookies(null)
        webView.loadUrl("https://www.douyin.com")
    }

    private fun checkLoginStatus() {
        val cookieString = CookieManager.getInstance().getCookie("https://www.douyin.com")
        if (!cookieString.isNullOrEmpty() && isLoggedIn(cookieString)) {
            // 保存 Cookie
            cookieStorage.saveCookies(cookieString)
            // 返回成功结果
            val resultIntent = Intent()
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun isLoggedIn(cookieString: String): Boolean {
        // 简单判断是否包含登录相关的关键 Cookie
        return cookieString.contains("passport_csrf_token") ||
                cookieString.contains("sessionid") ||
                cookieString.contains("sso_uid_tt")
    }
}