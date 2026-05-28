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
import com.zemin.downloader.core.PythonDownloadResult
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val cookieStorage = CookieStorage
    private val storageManager = StorageManager
    private val pythonBridge = PythonDownloadBridge
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
                val result = pythonBridge.download(inputText = shareText)

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
                    binding.tvInfo.text = formatDownloadSummary(
                        result = result,
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

    private fun formatDownloadSummary(
        result: PythonDownloadResult,
        mediaCount: Int,
        mediaRegisterMs: Int,
        taskTotalMs: Int,
    ): String {
        val visibleFiles = result.files
            .map(::File)
            .filterNot { it.name == "download_manifest.jsonl" }
            .filterNot { it.extension.equals("json", ignoreCase = true) }
            .filterNot { it.extension.equals("jsonl", ignoreCase = true) }
            .map { it.name }

        return buildString {
            append("完成")
            append(" · ")
            append(formatCounts(result))

            formatDownloadMetrics(
                result.downloadMetrics,
                result.apiMetrics,
                result.timings
            )?.let {
                append("\n")
                append(it)
            }

            formatTimings(result.timings)?.let {
                append("\n耗时: ")
                append(it)
            }

            append("\n总耗时: ${formatDuration(taskTotalMs)}")
            if (mediaRegisterMs > 0) {
                append(" · 媒体库 ${formatDuration(mediaRegisterMs)}")
            }
            if (mediaCount > 0) {
                append(" · 已登记 $mediaCount 个")
            }

            result.outputDir?.takeIf { it.isNotBlank() }?.let {
                append("\n保存: ")
                append(formatOutputDir(it))
            }

            if (visibleFiles.isNotEmpty()) {
                append("\n文件: ")
                append(visibleFiles.take(2).joinToString("\n") { ellipsizeMiddle(it, 38) })
                if (visibleFiles.size > 2) {
                    append("\n等 ${visibleFiles.size} 个文件")
                }
            }
        }
    }

    private fun formatCounts(result: PythonDownloadResult): String {
        val parts = mutableListOf("成功 ${result.success}")
        if (result.failed > 0) parts += "失败 ${result.failed}"
        if (result.skipped > 0) parts += "跳过 ${result.skipped}"
        return parts.joinToString(" / ")
    }

    private fun formatTimings(timings: Map<String, Int>): String? {
        if (timings.isEmpty()) return null
        val resolveCost = timings["resolve_input_url_ms"] ?: 0
        val parseCost = timings["parse_url_ms"] ?: 0
        val downloadCost = timings["download_content_ms"] ?: 0
        val totalCost = timings["total_ms"] ?: (resolveCost + parseCost + downloadCost)
        return listOfNotNull(
            "解析 ${formatDuration(resolveCost + parseCost)}",
            "下载 ${formatDuration(downloadCost)}",
            "总计 ${formatDuration(totalCost)}"
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
            " · 首包 ${formatDuration(primary.firstChunkMs)}"
        } else {
            ""
        }
        val detailCost = apiMetrics
            .filter { it.name == "get_video_detail" }
            .sumOf { it.durationMs }
            .takeIf { it > 0 } ?: apiMetrics.sumOf { it.durationMs }
        val transferCost = metrics.sumOf { it.durationMs }
        val downloadStage = (timings["download_content_ms"] ?: 0).coerceAtLeast(0)
        val otherCost = (downloadStage - detailCost - transferCost).coerceAtLeast(0)
        val host = (primary.finalHost ?: primary.host)?.let { ellipsizeMiddle(it, 24) }
        val detailParts = mutableListOf("文件 $size · $speed$firstChunk")
        if (!host.isNullOrBlank()) {
            detailParts += "CDN $host"
        }
        if (detailCost > 0) {
            detailParts += "详情 ${formatDuration(detailCost)}"
        }
        detailParts += "传输 ${formatDuration(transferCost)}"
        if (otherCost > 0) detailParts += "其他 ${formatDuration(otherCost)}"
        return detailParts.joinToString(" · ")
    }

    private fun formatOutputDir(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val marker = "/files/"
        val markerIndex = normalized.indexOf(marker)
        if (markerIndex >= 0) {
            return normalized.substring(markerIndex + marker.length)
        }
        return ellipsizeMiddle(normalized, 42)
    }

    private fun ellipsizeMiddle(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        if (maxLength <= 3) return value.take(maxLength)
        val keep = maxLength - 3
        val prefix = keep / 2
        val suffix = keep - prefix
        return value.take(prefix) + "..." + value.takeLast(suffix)
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
