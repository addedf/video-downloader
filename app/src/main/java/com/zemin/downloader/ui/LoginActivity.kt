package com.zemin.downloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zemin.downloader.core.CookieStorage

class LoginActivity : AppCompatActivity() {

    private companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var cookieStorage: CookieStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cookieStorage = CookieStorage(this)

        CookieManager.getInstance().setAcceptCookie(true)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = DEFAULT_USER_AGENT

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    checkLoginStatus()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkLoginStatus()
                }
            }
        }
        root.addView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        webView.loadUrl("https://www.douyin.com")
    }

    private fun checkLoginStatus() {
        val cookieString = CookieManager.getInstance().getCookie("https://www.douyin.com")
        if (!cookieString.isNullOrBlank() && isLoggedIn(cookieString)) {
            CookieManager.getInstance().flush()
            cookieStorage.saveCookies(cookieString)
            setResult(RESULT_OK, Intent())
            finish()
        }
    }

    private fun isLoggedIn(cookieString: String): Boolean {
        return cookieString.contains("sessionid") ||
            cookieString.contains("sso_uid_tt")
    }
}
