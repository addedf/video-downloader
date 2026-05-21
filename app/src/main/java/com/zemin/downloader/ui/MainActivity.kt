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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.PythonDownloadBridge
import com.zemin.downloader.core.StorageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoginState: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView

    private lateinit var cookieStorage: CookieStorage
    private lateinit var storageManager: StorageManager
    private lateinit var pythonBridge: PythonDownloadBridge
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        etUrl = findViewById(R.id.etUrl)
        btnLogin = findViewById(R.id.btnLogin)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvLoginState = findViewById(R.id.tvLoginState)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        cookieStorage = CookieStorage(this)
        storageManager = StorageManager(this)
        pythonBridge = PythonDownloadBridge(this)

        requestStoragePermissionIfNeeded()
        refreshLoginState()
        readSharedText(intent)

        btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        btnDownload.setOnClickListener {
            val input = etUrl.text.toString().trim()
            when {
                input.isEmpty() -> toast("请先粘贴抖音分享文本或链接")
                isDownloading -> toast("已有下载任务正在进行")
                else -> startDownload(input)
            }
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
        val cookieHeader = cookieStorage.getCookieString().orEmpty()
        if (cookieHeader.isBlank()) {
            showError("请先登录抖音获取 Cookie")
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            return
        }

        isDownloading = true
        setUiEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        tvStatus.text = "Python 核心正在解析和下载..."
        tvInfo.text = "输出目录: ${storageManager.getPythonDownloadDir().absolutePath}"

        lifecycleScope.launch {
            try {
                val result = pythonBridge.download(
                    inputText = shareText,
                    cookieHeader = cookieHeader,
                    outputDir = storageManager.getPythonDownloadDir()
                )

                progressBar.isIndeterminate = false
                progressBar.progress = 100

                val registeredUris = result.files
                    .map(::File)
                    .mapNotNull { storageManager.registerMediaFile(it) }

                if (result.ok || result.skipped > 0) {
                    tvStatus.text = "下载完成"
                    tvInfo.text = buildString {
                        append(result.message)
                        append("\n成功: ${result.success} 失败: ${result.failed} 跳过: ${result.skipped}")
                        append("\nPython 输出: ${result.outputDir}")
                        if (registeredUris.isNotEmpty()) {
                            append("\n已登记到系统媒体库: ${registeredUris.size} 个文件")
                        }
                        if (result.files.isNotEmpty()) {
                            append("\n新增文件:\n")
                            append(result.files.joinToString("\n") { File(it).name })
                        }
                    }
                    toast("下载完成")
                } else {
                    showError(result.error ?: result.message)
                }
            } catch (e: Exception) {
                showError("发生异常: ${e.message ?: "未知错误"}")
            } finally {
                isDownloading = false
                setUiEnabled(true)
                lifecycleScope.launch {
                    delay(1500)
                    progressBar.visibility = View.GONE
                    progressBar.isIndeterminate = false
                }
            }
        }
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
        val loggedIn = cookieStorage.getCookieString().isNullOrBlank().not()
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
}
