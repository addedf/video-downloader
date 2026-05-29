package com.zemin.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.LoginModule
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.common.util.MediaStorageManager
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    private val storageManager = MediaStorageManager
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

        requestStoragePermissionIfNeeded()
        refreshLoginState()
        readSharedText(intent)

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }

        binding.btnDownload.setOnClickListener {
            val input = binding.etUrl.text.toString().trim()
            when {
                input.isEmpty() -> toast("请先粘贴${currentTitle}分享文本或链接")
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
        if (LoginModule.needLogin) {
            val hasCookie = StoreModule.hasCookie()
            if (!hasCookie) {
                showError("请先登录${currentTitle}获取 Cookie")
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
                return
            }
        }

        isDownloading = true
        setUiEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = "Python 核心正在解析和下载..."
        binding.tvInfo.visibility = View.VISIBLE
        binding.tvInfo.text = "输出目录: ${storageManager.getPythonDownloadDir().absolutePath}"

        lifecycleScope.launch {
            val taskStartedAt = System.currentTimeMillis()
            try {
                storageManager.cleanupPythonDownloadCache()
                val result = DownloadModule.download(inputText = shareText)

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100

                val mediaRegisterStartedAt = System.currentTimeMillis()
                val registeredUris = result.files.map(::File).mapNotNull { file ->
                    storageManager.registerMediaFile(file)?.also {
                        storageManager.deleteTemporaryDownloadFile(file)
                    }
                }
                val mediaRegisterMs = (System.currentTimeMillis() - mediaRegisterStartedAt).toInt()
                val taskTotalMs = (System.currentTimeMillis() - taskStartedAt).toInt()

                if (result.ok || result.skipped > 0) {
                    storageManager.cleanupPythonDownloadSidecars()
                    binding.tvStatus.text = "下载完成"
                    binding.tvInfo.visibility = View.VISIBLE
                    binding.tvInfo.text = result.formatDownloadSummary(
                        mediaCount = registeredUris.size,
                        mediaRegisterMs = mediaRegisterMs,
                        taskTotalMs = taskTotalMs,
                    )
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
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE_PERM
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
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
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
        val loggedIn = StoreModule.hasCookie()
        binding.tvLoginState.text = if (loggedIn) "已登录" else "未登录"
        binding.btnLogin.text = if (loggedIn) "重新登录" else "登录"
        binding.accountDesc.text = "${currentTitle}账号"
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.etUrl.isEnabled = enabled
        binding.btnLogin.isEnabled = enabled
        binding.btnDownload.isEnabled = enabled
        binding.btnClear.isEnabled = true
    }

    private fun showError(message: String?) {
        binding.tvStatus.text = "出错了"
        binding.tvInfo.visibility = View.VISIBLE
        if (!message.isNullOrEmpty()) {
            binding.tvInfo.text = message
            toast(message)
        }
    }
}
