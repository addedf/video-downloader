package com.zemin.downloader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.PythonDownloadBridge
import com.zemin.downloader.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity<ActivityLoginBinding>(ActivityLoginBinding::inflate) {

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

    private val cookieStorage = CookieStorage
    private var loginDone = false
    private var loadedIesDouyin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        CookieManager.getInstance().setAcceptCookie(true)

        binding.webView.apply {
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

        binding.btnLoginAction.apply {
            setOnClickListener {
                finishLogin()
            }
        }

        binding.webView.loadUrl(LOGIN_URL)
    }

    private fun checkLoginStatus(view: WebView? = null) {
        val cookieString = collectCookies()
        if (cookieString.isNotBlank() && isLoggedIn(cookieString)) {
            loginDone = true
            binding.tvLoginStatus.text = "已登录，Cookie 已保存"
            binding.btnLoginAction.text = "完成"
            saveCookies(cookieString)
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
            saveCookies(cookieString)
        }
    }

    private fun saveCookies(cookieString: String) {
        cookieStorage.saveCookies(cookieString)
        lifecycleScope.launch {
            PythonDownloadBridge.refreshCookies(cookieString)
        }
    }

    private fun finishLogin() {
        if (loginDone) {
            CookieManager.getInstance().flush()
            saveCookiesFromWebView()
            setResult(RESULT_OK, Intent())
            Toast.makeText(this, "登录成功，Cookie 已保存", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun collectCookies(): String {
        return COOKIE_URLS
            .mapNotNull { CookieManager.getInstance().getCookie(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
    }
}
