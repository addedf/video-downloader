// service/DownloadService.kt
package com.zemin.downloader.download

import android.R
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import java.io.File

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadJob: Job? = null
    private var notificationBuilder: NotificationCompat.Builder? = null

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_HEADERS = "headers_map"
        const val NOTIFICATION_ID = 1001

        fun start(
            context: Context,
            videoUrl: String,
            filePath: String,
            headers: Map<String, String>
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_HEADERS, HashMap(headers))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val url = it.getStringExtra(EXTRA_VIDEO_URL) ?: return START_NOT_STICKY
            val filePath = it.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
            @Suppress("UNCHECKED_CAST")
            val headers = (it.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>)?.toMap() ?: emptyMap()
            startDownload(url, filePath, headers)
        }
        return START_STICKY
    }

    private fun startDownload(url: String, filePath: String, headers: Map<String, String>) {
        createNotification()
        startForeground(NOTIFICATION_ID, notificationBuilder!!.build())

        downloadJob = serviceScope.launch {
            val engine = DownloadEngine(OkHttpClient())
            val file = File(filePath)

            engine.downloadFile(url, file, headers).collectLatest { progress ->
                when (progress) {
                    is DownloadProgress.Progress -> updateNotification(progress.bytes, progress.total)
                    is DownloadProgress.Success -> {
                        showCompleteNotification(progress.file)
                        stopSelf()
                    }
                    is DownloadProgress.Error -> {
                        showErrorNotification(progress.exception.message ?: "下载失败")
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun createNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notificationBuilder = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("正在下载视频")
            .setContentText("准备中...")
            .setSmallIcon(R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setProgress(0, 0, true)
    }

    private fun updateNotification(bytesDownloaded: Long, totalBytes: Long) {
        val percent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
        notificationBuilder?.apply {
            setContentText("${formatSize(bytesDownloaded)} / ${formatSize(totalBytes)} ($percent%)")
            setProgress(100, percent, false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    private fun showCompleteNotification(file: File) {
        notificationBuilder?.apply {
            setContentTitle("下载完成")
            setContentText(file.name)
            setProgress(0, 0, false)
            setOngoing(false)
            setSmallIcon(R.drawable.stat_sys_download_done)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    private fun showErrorNotification(errorMsg: String) {
        notificationBuilder?.apply {
            setContentTitle("下载失败")
            setContentText(errorMsg)
            setProgress(0, 0, false)
            setOngoing(false)
            setSmallIcon(R.drawable.stat_notify_error)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        serviceScope.cancel()
    }
}