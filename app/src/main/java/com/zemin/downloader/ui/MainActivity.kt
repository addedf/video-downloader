package com.zemin.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.DouyinApiClient
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.core.SignatureProvider
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.core.VideoParser
import com.zemin.downloader.parse.ResolveResult
import com.zemin.downloader.parse.UrlResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoginState: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView

    private lateinit var cookieStorage: CookieStorage
    private lateinit var signatureProvider: SignatureProvider
    private lateinit var storageManager: StorageManager
    private var isDownloading = false

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshLoginState()
    }

    companion object {
        private const val REQUEST_STORAGE_PERM = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnLogin = findViewById(R.id.btnLogin)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvLoginState = findViewById(R.id.tvLoginState)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        cookieStorage = CookieStorage(this)
        signatureProvider = SignatureProvider(this)
        storageManager = StorageManager(this)

        lifecycleScope.launch {
            signatureProvider.preload()
        }

        requestStoragePermissionIfNeeded()
        refreshLoginState()
        readSharedText(intent)

        btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        btnDownload.setOnClickListener {
            val input = etUrl.text.toString().trim()
            if (input.isEmpty()) {
                toast("请先粘贴抖音分享文本或链接")
                return@setOnClickListener
            }
            if (isDownloading) {
                toast("已有下载任务进行中")
                return@setOnClickListener
            }
            startDownload(input)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLoginState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)
    }

    private fun startDownload(shareText: String) {
        val cookies = cookieStorage.getCookiesMap()
        if (cookies.isEmpty()) {
            showError("请先登录抖音获取 Cookie")
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            return
        }

        isDownloading = true
        setUiEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "正在解析分享内容..."
        tvInfo.text = ""

        lifecycleScope.launch {
            try {
                val awemeId = when (val result = UrlResolver.resolve(shareText)) {
                    is ResolveResult.Success -> result.awemeId
                    is ResolveResult.Error -> {
                        showError(result.message)
                        return@launch
                    }
                }

                tvStatus.text = "正在获取视频信息..."
                tvInfo.text = "aweme_id: $awemeId"

                signatureProvider.preload()
                val apiClient = DouyinApiClient(cookies, signatureProvider)
                val json = apiClient.requestAwemeDetail(awemeId)
                if (json.isNullOrBlank()) {
                    showError("获取视频信息失败，请重新登录后再试")
                    return@launch
                }

                val video = VideoParser.parseAwemeDetail(json)
                if (video == null) {
                    showError("解析视频地址失败，可能是 Cookie、签名或作品权限问题")
                    return@launch
                }

                val safeAwemeId = video.awemeId.ifBlank { awemeId }
                val outputFile = storageManager.getVideoOutputFile(
                    "douyin_${safeAwemeId}_${System.currentTimeMillis()}.mp4"
                )

                val downloadRequest = apiClient.buildVideoDownloadRequest(json)
                if (downloadRequest == null) {
                    showError("构造视频下载地址失败，可能是 Cookie、签名或作品权限问题")
                    return@launch
                }

                tvStatus.text = "正在下载..."
                tvInfo.text = "视频：${video.desc.ifBlank { "无标题" }}\n作者：${video.authorName}"

                DownloadEngine(apiClient.downloadClient()).downloadFile(
                    downloadRequest.url,
                    outputFile,
                    downloadRequest.headers
                )
                    .collect { progress ->
                        when (progress) {
                            is DownloadProgress.Progress -> updateProgress(progress)
                            is DownloadProgress.Success -> {
                                progressBar.progress = 100
                                val uri = storageManager.registerToMediaStore(progress.file)
                                tvStatus.text = "下载完成"
                                tvInfo.text = "已保存到相册 Movies/Douyin\n${uri ?: progress.file.absolutePath}"
                                toast("下载完成")
                            }
                            is DownloadProgress.Error -> {
                                showError("下载失败：${progress.exception.message ?: "未知错误"}")
                            }
                        }
                    }
            } catch (e: Exception) {
                showError("发生异常：${e.message ?: "未知错误"}")
            } finally {
                isDownloading = false
                setUiEnabled(true)
                lifecycleScope.launch {
                    delay(1500)
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun updateProgress(progress: DownloadProgress.Progress) {
        val percent = if (progress.total > 0) {
            ((progress.bytes * 100) / progress.total).toInt()
        } else {
            0
        }
        progressBar.progress = percent
        tvStatus.text = "已下载 ${formatSize(progress.bytes)} / ${formatSize(progress.total)}"
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERM
            )
        }
    }

    private fun readSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (sharedText.isNotBlank()) {
            etUrl.setText(sharedText)
            tvStatus.text = "已接收分享文本"
        }
    }

    private fun refreshLoginState() {
        val loggedIn = cookieStorage.getCookiesMap().isNotEmpty()
        tvLoginState.text = if (loggedIn) "已登录" else "未登录"
        btnLogin.text = if (loggedIn) "重新登录抖音" else "登录抖音"
    }

    private fun setUiEnabled(enabled: Boolean) {
        etUrl.isEnabled = enabled
        btnLogin.isEnabled = enabled
        btnDownload.isEnabled = enabled
    }

    private fun showError(message: String) {
        tvStatus.text = "出错了"
        tvInfo.text = message
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
