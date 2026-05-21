package com.zemin.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.PythonDownloadBridge
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

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
        private val URL_PATTERN = Regex("https?://\\S+")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cookieStorage = CookieStorage(this)
        storageManager = StorageManager(this)
        pythonBridge = PythonDownloadBridge(this)

        requestStoragePermissionIfNeeded()
        refreshLoginState()
        readSharedText(intent)

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        binding.btnDownload.setOnClickListener {
            val input = binding.etUrl.text.toString().trim()
            when {
                input.isEmpty() -> toast("请先粘贴抖音分享文本或链接")
                isDownloading -> toast("已有下载任务正在进行")
                else -> startDownload(input)
            }
        }

        binding.btnClear.setOnClickListener {
            clearLinkAndCancelDownload()
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
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = "Python 核心正在解析和下载..."
        binding.tvInfo.visibility = View.VISIBLE
        binding.tvInfo.text = "输出目录: ${storageManager.getPythonDownloadDir().absolutePath}"

        lifecycleScope.launch {
            try {
                val result = pythonBridge.download(
                    inputText = shareText,
                    cookieHeader = cookieHeader,
                    outputDir = storageManager.getPythonDownloadDir()
                )

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100

                val registeredUris = result.files
                    .map(::File)
                    .mapNotNull { storageManager.registerMediaFile(it) }

                if (result.ok || result.skipped > 0) {
                    binding.tvStatus.text = "下载完成"
                    binding.tvInfo.visibility = View.VISIBLE
                    binding.tvInfo.text = buildString {
                        append(result.message)
                        append("\n成功: ${result.success} ,失败: ${result.failed} ,跳过: ${result.skipped}")
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
                    binding.progressBar.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun clearLinkAndCancelDownload() {
        binding.etUrl.text?.clear()
        binding.tvStatus.text = "已清空链接"
        binding.tvInfo.visibility = View.GONE
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
        val sharedText = extractSharedText(intent)
        if (sharedText.isNotBlank()) {
            binding.etUrl.setText(sharedText)
            binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
            binding.tvStatus.text = "已接收分享链接"
            binding.tvInfo.visibility = View.GONE
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        if (intent == null) return ""
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: ""
                normalizeSharedText(rawText)
            }

            Intent.ACTION_VIEW -> {
                normalizeSharedText(intent.dataString.orEmpty())
            }

            else -> ""
        }
    }

    private fun normalizeSharedText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val url = URL_PATTERN.find(trimmed)?.value?.trimEnd('.', ',', ';', '，', '。', '；', ')')
        return url ?: trimmed
    }

    private fun refreshLoginState() {
        val loggedIn = cookieStorage.getCookieString().isNullOrBlank().not()
        binding.tvLoginState.text = if (loggedIn) "已登录" else "未登录"
        binding.btnLogin.text = if (loggedIn) "重新登录抖音" else "登录抖音"
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.etUrl.isEnabled = enabled
        binding.btnLogin.isEnabled = enabled
        binding.btnDownload.isEnabled = enabled
        binding.btnClear.isEnabled = true
    }

    private fun showError(message: String) {
        binding.tvStatus.text = "出错了"
        binding.tvInfo.visibility = View.VISIBLE
        binding.tvInfo.text = message
        toast(message)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
