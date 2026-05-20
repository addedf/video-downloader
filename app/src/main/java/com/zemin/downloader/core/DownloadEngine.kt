// download/DownloadEngine.kt
package com.zemin.downloader.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class DownloadEngine(private val client: OkHttpClient) {

    /**
     * 下载文件并返回进度 Flow
     */
    fun downloadFile(
        url: String,
        destination: File,
        headers: Map<String, String> = emptyMap()
    ): Flow<DownloadProgress> = flow {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            emit(DownloadProgress.Error(IOException("HTTP ${response.code}")))
            return@flow
        }

        val body = response.body ?: run {
            emit(DownloadProgress.Error(IOException("Empty body")))
            return@flow
        }

        val totalBytes = body.contentLength()
        var downloadedBytes = 0L

        FileOutputStream(destination).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    emit(DownloadProgress.Progress(downloadedBytes, totalBytes))
                }
            }
        }
        emit(DownloadProgress.Success(destination))
    }.flowOn(Dispatchers.IO)
}

sealed class DownloadProgress {
    data class Progress(val bytes: Long, val total: Long) : DownloadProgress()
    data class Success(val file: File) : DownloadProgress()
    data class Error(val exception: Throwable) : DownloadProgress()
}