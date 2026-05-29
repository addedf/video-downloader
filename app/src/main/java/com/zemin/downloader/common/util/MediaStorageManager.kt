package com.zemin.downloader.common.util

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.zemin.downloader.appContext
import java.io.File

object MediaStorageManager {
    const val MEDIA_DOWNLOAD_DIR = "Download"

    fun getAppFileDir(): File {
        return File(appContext.filesDir, "python-runtime").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getPythonDownloadDir(): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(appContext.cacheDir, "python-downloads")
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MEDIA_DOWNLOAD_DIR
            )
        }.apply {
            if (!exists()) mkdirs()
        }
    }

    fun cleanupPythonDownloadCache() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        getPythonDownloadDir().deleteRecursively()
        getPythonDownloadDir().mkdirs()
    }

    fun cleanupPythonDownloadSidecars() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cacheRoot = getPythonDownloadDir()
        cacheRoot.walkBottomUp().forEach { file ->
            if (file == cacheRoot) return@forEach
            if (file.isDirectory) {
                if (file.list()?.isEmpty() == true) file.delete()
                return@forEach
            }
            val extension = file.extension.lowercase()
            val isMedia = extension in MEDIA_EXTENSIONS
            if (!isMedia || extension == "tmp") {
                file.delete()
            }
        }
    }

    fun deleteTemporaryDownloadFile(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val cacheRoot = getPythonDownloadDir().canonicalFile
        val target = file.canonicalFile
        if (!target.path.startsWith(cacheRoot.path + File.separator)) return false
        val deleted = target.delete()
        pruneEmptyParents(target.parentFile, cacheRoot)
        return deleted
    }

    private fun pruneEmptyParents(start: File?, stopAt: File) {
        var current = start
        while (current != null && current != stopAt) {
            val children = current.list()
            if (children == null || children.isNotEmpty()) return
            if (!current.delete()) return
            current = current.parentFile
        }
    }

    fun registerMediaFile(file: File): Uri? {
        val extension = file.extension.lowercase()
        val mimeType = mimeTypeForExtension(extension) ?: return null
        return when {
            mimeType.startsWith("video/") -> registerVideoToMediaStore(file, mimeType)
            mimeType.startsWith("image/") -> registerImageToMediaStore(file, mimeType)
            else -> null
        }
    }

    private fun mimeTypeForExtension(extension: String): String? {
        return when (extension) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> null
        }
    }

    private val MEDIA_EXTENSIONS =
        setOf("mp4", "mov", "m4a", "mp3", "jpg", "jpeg", "png", "webp", "gif")

    private fun registerVideoToMediaStore(file: File, mimeType: String = "video/mp4"): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$MEDIA_DOWNLOAD_DIR")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = appContext.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            appContext.sendBroadcast(intent)
            Uri.fromFile(file)
        }
    }

    private fun registerImageToMediaStore(file: File, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$MEDIA_DOWNLOAD_DIR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = appContext.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null

            appContext.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            appContext.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            appContext.sendBroadcast(intent)
            Uri.fromFile(file)
        }
    }
}