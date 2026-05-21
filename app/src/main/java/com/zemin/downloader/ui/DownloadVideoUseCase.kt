// domain/DownloadVideoUseCase.kt
package com.zemin.downloader.ui

import com.zemin.downloader.core.DouyinApiClient
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.core.VideoParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DownloadVideoUseCase(
    private val apiClient: DouyinApiClient,
    private val storageManager: StorageManager
) {
    /**
     * 执行单视频下载
     * @param awemeId 视频ID
     * @return Flow<DownloadProgress> 进度流
     */
    fun execute(awemeId: String): Flow<DownloadProgress> = flow {
        // 1. 请求视频详情
        val json = apiClient.requestAwemeDetail(awemeId)
            ?: throw Exception("Failed to get video detail")

        // 2. 解析视频信息
        val video = VideoParser.parseAwemeDetail(json)
            ?: throw Exception("Failed to parse video info")

        // 3. 准备输出文件
        val fileName = "douyin_${video.awemeId}_${System.currentTimeMillis()}.mp4"
        val outputFile = storageManager.getVideoOutputFile(fileName)

        // 4. 下载视频（复用 API 客户端的签名、Cookie 和下载头）
        val downloadRequest = apiClient.buildVideoDownloadRequest(json)
            ?: throw Exception("Failed to build video download request")
        val downloadFlow = DownloadEngine(apiClient.downloadClient()).downloadFile(
            downloadRequest.url,
            outputFile,
            downloadRequest.headers
        )
        downloadFlow.collect { progress ->
            if (progress is DownloadProgress.Success) {
                // 5. 注册到媒体库
                storageManager.registerToMediaStore(outputFile)
            }
            emit(progress)
        }
    }
}
