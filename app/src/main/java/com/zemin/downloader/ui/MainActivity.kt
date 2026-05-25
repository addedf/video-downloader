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
import kotlin.math.roundToInt

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
            val taskStartedAt = System.currentTimeMillis()
            try {
                val result = pythonBridge.download(
                    inputText = shareText,
                    cookieHeader = cookieHeader,
                    outputDir = storageManager.getPythonDownloadDir()
                )

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100

                val mediaRegisterStartedAt = System.currentTimeMillis()
                val registeredUris = result.files
                    .map(::File)
                    .mapNotNull { storageManager.registerMediaFile(it) }
                val mediaRegisterMs = (System.currentTimeMillis() - mediaRegisterStartedAt).toInt()
                val taskTotalMs = (System.currentTimeMillis() - taskStartedAt).toInt()

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
                        formatTimings(result.timings)?.let {
                            append("\n耗时: ")
                            append(it)
                        }
                        append("\nApp总耗时: ${formatDuration(taskTotalMs)}")
                        if (mediaRegisterMs > 0) {
                            append("，媒体库登记 ${formatDuration(mediaRegisterMs)}")
                        }
                        formatDownloadMetrics(
                            result.downloadMetrics,
                            result.apiMetrics,
                            result.timings
                        )?.let {
                            append("\n")
                            append(it)
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

    private fun formatTimings(timings: Map<String, Int>): String? {
        if (timings.isEmpty()) return null
        val prepareMs = timings["prepare_ms"] ?: 0
        val databaseMs = timings["database_ms"] ?: prepareMs
        val resolveMs = timings["resolve_ms"] ?: databaseMs
        val downloadMs = timings["download_ms"] ?: resolveMs
        val collectMs = timings["collect_files_ms"] ?: downloadMs

        val resolveCost = (resolveMs - databaseMs).coerceAtLeast(0)
        val downloadCost = (downloadMs - resolveMs).coerceAtLeast(0)
        val collectCost = (collectMs - downloadMs).coerceAtLeast(0)
        return listOfNotNull(
            "解析 ${formatDuration(resolveCost)}",
            "下载 ${formatDuration(downloadCost)}",
            "收尾 ${formatDuration(collectCost)}",
            "总计 ${formatDuration(collectMs)}"
        ).joinToString(" / ").takeIf { it.isNotBlank() }
    }

    private fun formatDownloadMetrics(
        metrics: List<com.zemin.downloader.core.DownloadMetric>,
        apiMetrics: List<com.zemin.downloader.core.ApiMetric>,
        timings: Map<String, Int>
    ): String? {
        val primary = metrics.firstOrNull { it.ok && it.bytes > 0 } ?: return null
        val size = formatBytes(primary.bytes)
        val speed = formatSpeed(primary.speedKbps)
        val firstChunk = if (primary.firstChunkMs > 0) {
            "，首包 ${formatDuration(primary.firstChunkMs)}"
        } else {
            ""
        }
        val apiCost = apiMetrics.sumOf { it.durationMs }
        val transferCost = metrics.sumOf { it.durationMs }
        val downloadStage = ((timings["download_ms"] ?: 0) - (timings["resolve_ms"] ?: 0))
            .coerceAtLeast(0)
        val otherCost = (downloadStage - apiCost - transferCost).coerceAtLeast(0)
        val detailParts = mutableListOf("下载明细: $size，均速 $speed$firstChunk")
        if (apiCost > 0) {
            detailParts += "详情接口 ${formatDuration(apiCost)}${formatApiAttempts(apiMetrics)}"
        }
        detailParts += "文件传输 ${formatDuration(transferCost)}"
        if (otherCost > 0) detailParts += "其他 ${formatDuration(otherCost)}"
        return detailParts.joinToString("，")
    }

    private fun formatApiAttempts(apiMetrics: List<com.zemin.downloader.core.ApiMetric>): String {
        val attempts = apiMetrics.flatMap { it.attempts }
        if (attempts.isEmpty()) return ""
        val text = attempts.joinToString("/") { attempt ->
            val status = when {
                attempt.ok -> "成功"
                !attempt.error.isNullOrBlank() -> attempt.error
                !attempt.filterReason.isNullOrBlank() -> attempt.filterReason
                else -> "无数据"
            }
            val stages = listOfNotNull(
                attempt.tokenMs.takeIf { it > 0 }?.let {
                    val source = attempt.tokenSource?.let { source -> ":$source" }.orEmpty()
                    "token$source ${formatDuration(it)}"
                },
                attempt.signMs.takeIf { it > 0 }?.let { "签名 ${formatDuration(it)}" },
                attempt.httpMs.takeIf { it > 0 }?.let { "HTTP ${formatDuration(it)}" },
                attempt.status.takeIf { it > 0 && it != 200 }?.let { "status $it" }
            ).joinToString(", ")
            val stageText = if (stages.isBlank()) "" else " [$stages]"
            "${attempt.aid}:${formatDuration(attempt.durationMs)} $status$stageText"
        }
        return "($text)"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${(kb * 10).roundToInt() / 10.0}KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${(mb * 10).roundToInt() / 10.0}MB"
        return "${(mb / 102.4).roundToInt() / 10.0}GB"
    }

    private fun formatSpeed(kbps: Int): String {
        if (kbps <= 0) return "未知"
        return if (kbps < 1024) {
            "${kbps}KB/s"
        } else {
            "${(kbps / 102.4).roundToInt() / 10.0}MB/s"
        }
    }

    private fun formatDuration(ms: Int): String {
        return if (ms >= 1000) {
            "${(ms / 100.0).roundToInt() / 10.0}s"
        } else {
            "${ms}ms"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
