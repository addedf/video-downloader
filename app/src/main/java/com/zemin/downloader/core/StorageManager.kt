// storage/StorageManager.kt
package com.zemin.downloader.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

class StorageManager(private val context: Context) {

    /**
     * 获取视频输出文件，优先使用 MediaStore（Android 10+），否则用传统外部存储
     */
    fun getVideoOutputFile(fileName: String): File {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Douyin")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            // 从 URI 无法直接获取文件路径，但我们仍需要一个临时文件来写入，然后通过 URI 写入
            // 简单起见，这里仍返回传统文件对象，下载完成后再插入 MediaStore
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val douyinDir = File(dir, "Douyin")
        if (!douyinDir.exists()) douyinDir.mkdirs()
        return File(douyinDir, fileName)
    }

    /**
     * 将下载好的文件注册到 MediaStore，使其在相册可见
     */
    fun registerToMediaStore(file: File, mimeType: String = "video/mp4") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Douyin")
            }
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
        } else {
            // 9 及以下：发送广播通知
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(file)
            context.sendBroadcast(intent)
        }
    }
}