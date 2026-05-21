package com.zemin.downloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zemin.downloader.core.CookieStorage

class LoginActivity : AppCompatActivity() {

    private companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"
        private const val LOGIN_URL = "https://sso.douyin.com/login/?service=https://www.douyin.com&type=phone"
        private const val IES_DOUYIN_URL = "https://www.iesdouyin.com/"
        private val COOKIE_URLS = listOf(
            "https://douyin.com",
            "https://www.douyin.com",
            "https://sso.douyin.com",
            "https://login.douyin.com",
            IES_DOUYIN_URL
        )
    }

    private lateinit var webView: WebView
    private lateinit var cookieStorage: CookieStorage
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private var loginDone = false
    private var loadedIesDouyin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        cookieStorage = CookieStorage(this)

        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleBar = TextView(this).apply {
            text = "登录抖音"
            textSize = 20f
            setTextColor(Color.rgb(32, 32, 32))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.userAgentString = DEFAULT_USER_AGENT
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    checkLoginStatus()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkLoginStatus(view)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return url.startsWith("snssdk")
                }
            }
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        statusText = TextView(this).apply {
            text = "输入手机号和验证码完成登录"
            textSize = 14f
            setTextColor(Color.rgb(96, 96, 96))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        actionButton = Button(this).apply {
            text = "取消"
            setOnClickListener {
                if (loginDone) {
                    CookieManager.getInstance().flush()
                    saveCookiesFromWebView()
                    setResult(RESULT_OK, Intent())
                    Toast.makeText(this@LoginActivity, "登录成功，Cookie 已保存", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }

        bottomBar.addView(statusText)
        bottomBar.addView(actionButton)
        root.addView(titleBar)
        root.addView(webView)
        root.addView(bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        webView.loadUrl(LOGIN_URL)
    }

    private fun checkLoginStatus(view: WebView? = null) {
        val cookieString = collectCookies()
        if (cookieString.isNotBlank() && isLoggedIn(cookieString)) {
            loginDone = true
            statusText.text = "已登录，Cookie 已保存"
            actionButton.text = "完成"
            cookieStorage.saveCookies(cookieString)
            CookieManager.getInstance().flush()
            if (!loadedIesDouyin) {
                loadedIesDouyin = true
                view?.loadUrl(IES_DOUYIN_URL)
            }
        }
    }

    private fun isLoggedIn(cookieString: String): Boolean {
        return cookieString.contains("sessionid") ||
            cookieString.contains("sso_uid_tt")
    }

    private fun saveCookiesFromWebView() {
        val cookieString = collectCookies()
        if (cookieString.isNotBlank()) {
            cookieStorage.saveCookies(cookieString)
        }
    }

    private fun collectCookies(): String {
        return COOKIE_URLS
            .mapNotNull { CookieManager.getInstance().getCookie(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
