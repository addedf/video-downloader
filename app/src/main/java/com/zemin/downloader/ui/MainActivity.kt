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
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.bean.PythonDownloadResult
import com.zemin.downloader.common.util.LocalStorage
import com.zemin.downloader.impl.dy.DyDownloadBridge
import com.zemin.downloader.common.util.MediaStorageManager
import com.zemin.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {

    private val cookieStorage = LocalStorage
    private val storageManager = MediaStorageManager
    private val pythonBridge = DyDownloadBridge
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
        // if (cookieHeader.isBlank()) {
        //     showError("请先登录抖音获取 Cookie")
        //     loginLauncher.launch(Intent(this, LoginActivity::class.java))
        //     return
        // }

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
                val result = pythonBridge.download(inputText = shareText)

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 100

                val mediaRegisterStartedAt = System.currentTimeMillis()
                val registeredUris = result.files
                    .map(::File)
                    .mapNotNull { file ->
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
            .map { compactFileName(it.name) }
            .distinct()

        return buildString {
            append("完成：")
            append(formatCounts(result))

            formatProcessLog(
                result = result,
                mediaRegisterMs = mediaRegisterMs,
                taskTotalMs = taskTotalMs,
            )?.let {
                append("\n")
                append(it)
            }

            if (mediaCount > 0) {
                append("\n下载目录：$mediaCount 个 · ")
                append(formatOutputDir(result.outputDir.orEmpty()))
            } else {
                result.outputDir?.takeIf { it.isNotBlank() }?.let {
                    append("\n下载目录：")
                    append(formatOutputDir(it))
                }
            }

            if (visibleFiles.isNotEmpty()) {
                append("\n文件名称：")
                append(visibleFiles.take(2).joinToString("、"))
                if (visibleFiles.size > 2) {
                    append(" 等 ${visibleFiles.size} 个")
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

    private fun formatProcessLog(
        result: PythonDownloadResult,
        mediaRegisterMs: Int,
        taskTotalMs: Int,
    ): String? {
        val metrics = result.downloadMetrics
        val apiMetrics = result.apiMetrics
        val timings = result.timings
        val primary = metrics.firstOrNull { it.ok && it.bytes > 0 } ?: return null
        val size = formatBytes(primary.bytes)
        val speed = formatSpeed(primary.speedKbps)
        val firstChunk = if (primary.firstChunkMs > 0) {
            "首包 ${formatDuration(primary.firstChunkMs)}"
        } else {
            null
        }
        val resolveCost = (timings["resolve_input_url_ms"] ?: 0) +
            (timings["parse_url_ms"] ?: 0)
        val detailCost = apiMetrics
            .filter { it.name == "get_video_detail" }
            .sumOf { it.durationMs }
            .takeIf { it > 0 } ?: apiMetrics.sumOf { it.durationMs }
        val transferCost = metrics.sumOf { it.durationMs }
        val downloadStage = (timings["download_content_ms"] ?: 0).coerceAtLeast(0)
        val otherCost = (downloadStage - detailCost - transferCost).coerceAtLeast(0)
        val host = (primary.finalHost ?: primary.host)?.let { ellipsizeMiddle(it, 24) }
        val firstLine = mutableListOf<String>()
        if (resolveCost > 0) firstLine += "解析 ${formatDuration(resolveCost)}"
        if (detailCost > 0) firstLine += "详情 ${formatDuration(detailCost)}"
        firstLine += "传输 ${formatDuration(transferCost)}"

        val secondLine = mutableListOf<String>()
        if (mediaRegisterMs > 0) secondLine += "入库 ${formatDuration(mediaRegisterMs)}"
        if (otherCost > 0) secondLine += "其他 ${formatDuration(otherCost)}"
        secondLine += "总计 ${formatDuration(taskTotalMs)}"

        val fileLine = mutableListOf("文件 $size", "均速 $speed")
        firstChunk?.let { fileLine += it }

        return buildString {
            append("耗时：")
            append(firstLine.joinToString(" · "))
            append("\n")
            append("      ")
            append(secondLine.joinToString(" · "))
            append("\n下载：")
            append(fileLine.joinToString(" · "))
            if (!host.isNullOrBlank()) {
                append("\nCDN：")
                append(host)
            }
        }
    }

    private fun formatOutputDir(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.contains("/cache/python-downloads")) return "Movies/Douyin"
        if (normalized.contains("/Movies/Douyin")) return "Movies/Douyin"
        return ellipsizeMiddle(normalized.substringAfterLast("/"), 32)
    }

    private fun compactFileName(name: String): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val compact = stem
            .replace(Regex("^\\d{4}-\\d{2}-\\d{2}_?"), "")
            .replace(Regex("_?\\d{15,20}$"), "")
            .ifBlank { stem }
        val shortName = ellipsizeMiddle(compact, 24)
        return if (extension.isBlank()) shortName else "$shortName.$extension"
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
