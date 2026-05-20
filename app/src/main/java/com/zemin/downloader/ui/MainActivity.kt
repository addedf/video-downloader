package com.zemin.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.core.DouyinApiClient
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.core.SignatureProvider
import com.zemin.downloader.core.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView

    private lateinit var signatureProvider: SignatureProvider
    private lateinit var okHttpClient: OkHttpClient
    private var isDownloading = false

    companion object {
        private const val REQUEST_STORAGE_PERM = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)

        // 初始化签名引擎和网络客户端
        signatureProvider = SignatureProvider(this)
        okHttpClient = OkHttpClient()

        // 预加载签名 JS（异步）
        lifecycleScope.launch {
            signatureProvider.preload()
        }

        // 请求存储权限（Android 10 以下需要，10+ 用 MediaStore 可无需权限）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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

        btnDownload.setOnClickListener {
            val input = etUrl.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "请先输入视频链接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isDownloading) {
                Toast.makeText(this, "已有下载任务进行中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startDownload(input)
        }
    }

    private fun startDownload(shareUrl: String) {
        // 这里我们假设已经登录并获得了 Cookie（实际你可用 WebView 登录后保存）
        // 为了快速跑通 Demo，可以从本地配置读取一个已测试过的 Cookie 字符串
        val testCookies = getTestCookies() // 一个硬编码的 cookie 映射，需要你替换为真实值
        if (testCookies.isEmpty()) {
            Toast.makeText(this, "请先登录获取Cookie", Toast.LENGTH_LONG).show()
            return
        }

        isDownloading = true
        setUiEnabled(false)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvStatus.text = "解析链接..."

        lifecycleScope.launch {
            try {
                // 1. 解析短链接，提取 aweme_id
                val awemeId = withContext(Dispatchers.IO) {
                    resolveAwemeId(shareUrl)
                }
                if (awemeId == null) {
                    showError("无法解析视频ID，请检查链接")
                    return@launch
                }
                tvStatus.text = "获取视频信息..."
                tvInfo.text = "aweme_id: $awemeId"

                // 2. 请求 API 获取视频详情
                val apiClient = DouyinApiClient(testCookies, signatureProvider)
                val json = apiClient.requestAwemeDetail(awemeId)
                if (json == null) {
                    showError("获取视频信息失败，可能Cookie或签名错误")
                    return@launch
                }

                // 3. 解析无水印地址
                val video = VideoParser.parseAwemeDetail(json)
                if (video == null) {
                    showError("解析视频地址失败")
                    return@launch
                }
                tvInfo.text = "视频：${video.desc} by ${video.authorName}"

                // 4. 准备本地保存路径
                val outputFile = getOutputFile(video.awemeId)

                // 5. 下载并更新进度
                val downloadEngine = DownloadEngine(okHttpClient)
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Referer" to "https://www.douyin.com/"
                )
                tvStatus.text = "下载中..."

                downloadEngine.downloadFile(video.videoUrl, outputFile, headers)
                    .collect { progress ->
                        when (progress) {
                            is DownloadProgress.Progress -> {
                                val percent = if (progress.total > 0) {
                                    ((progress.bytes * 100) / progress.total).toInt()
                                } else 0
                                progressBar.progress = percent
                                tvStatus.text = "已下载 ${formatSize(progress.bytes)} / ${formatSize(progress.total)}"
                            }
                            is DownloadProgress.Success -> {
                                progressBar.progress = 100
                                tvStatus.text = "下载完成"
                                tvInfo.text = "保存至：${progress.file.absolutePath}"
                                // 通知媒体库扫描（Android 10+ 用 MediaStore，这里简单发送广播）
                                sendBroadcast(
                                    Intent(
                                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                        Uri.fromFile(progress.file)
                                    )
                                )
                                Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
                            }
                            is DownloadProgress.Error -> {
                                showError("下载失败：${progress.exception.message}")
                            }
                        }
                    }
            } catch (e: Exception) {
                showError("发生异常：${e.message}")
            } finally {
                isDownloading = false
                setUiEnabled(true)
                // 保持进度条可见一段时间后隐藏
                lifecycleScope.launch {
                    delay(1500)
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        etUrl.isEnabled = enabled
        btnDownload.isEnabled = enabled
    }

    private fun showError(msg: String) {
        tvStatus.text = "错误"
        tvInfo.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 解析短链接获取 aweme_id
     */
    private fun resolveAwemeId(shortUrl: String): String? {
        return try {
            // 如果是长链接直接提取
            val pattern = Regex("video/(\\d+)")
            pattern.find(shortUrl)?.groupValues?.get(1) ?: run {
                // 否则按短链接处理，跟随重定向拿到真实 URL
                val client = okHttpClient.newBuilder().followRedirects(false).build()
                val response = client.newCall(
                    Request.Builder().url(shortUrl).build()
                ).execute()
                val location = response.header("Location") ?: return null
                pattern.find(location)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getOutputFile(awemeId: String): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 使用外部公共目录，实际应通过 MediaStore 写入，这里简单返回缓存文件
            // 最终你可以用 StorageManager（之前给过的）来保存到 Movies 并注册
            getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "douyin_${awemeId}_${System.currentTimeMillis()}.mp4")
    }

    /**
     * 获取测试用 Cookie，你需要替换为从 WebView 登录后保存的真实 Cookie
     * 格式：Map<String, String>，键名如 "sessionid", "passport_csrf_token" 等
     */
    private fun getTestCookies(): Map<String, String> {
        // TODO: 这里填入你登录后获取的 Cookie 键值对，否则下载会失败
        return mapOf(
            // "sessionid" to "your_session_id_here",
            // "passport_csrf_token" to "your_token_here",
            // ... 其他必要字段
        )
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}