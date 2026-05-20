// ui/DownloadViewModel.kt (追加内容)
package com.zemin.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.DouyinApiClient
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.core.SignatureProvider
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.core.VideoParser
import com.zemin.downloader.download.DownloadService
import com.zemin.downloader.download.DownloadUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val signatureProvider = SignatureProvider(application).also {
        viewModelScope.launch { it.preload() }
    }

    private val cookieStorage = CookieStorage(application)
    private val okHttpClient = OkHttpClient()
    private val storageManager = StorageManager(application)

    // 登录状态
    private val _loginState = MutableStateFlow<LoginState>(LoginState.Unknown)
    val loginState: StateFlow<LoginState> = _loginState

    // 下载状态
    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val downloadState: StateFlow<DownloadUiState> = _downloadState

    // 下载进度
    private val _progressEvents = MutableSharedFlow<DownloadProgress>()
    val progressEvents = _progressEvents.asSharedFlow()

    init {
        checkLoginStatus()
    }

    fun checkLoginStatus() {
        val cookies = cookieStorage.getCookieString()
        if (cookies != null && isCookieValid(cookies)) {
            _loginState.value = LoginState.LoggedIn
        } else {
            _loginState.value = LoginState.NotLoggedIn
        }
    }

    fun clearLogin() {
        cookieStorage.clear()
        checkLoginStatus()
    }

    // ui/DownloadViewModel.kt 中的下载方法改为：
    fun downloadVideo(awemeId: String) {
        viewModelScope.launch {
            val cookiesMap = cookieStorage.getCookiesMap()
            if (cookiesMap.isEmpty()) {
                _downloadState.value = DownloadUiState.Error("请先登录")
                return@launch
            }

            _downloadState.value = DownloadUiState.Downloading

            try {
                // 1. 获取视频详情和无水印 URL
                val apiClient = DouyinApiClient(cookiesMap, signatureProvider)
                val json = apiClient.requestAwemeDetail(awemeId)
                    ?: throw Exception("获取视频信息失败")
                val video = VideoParser.parseAwemeDetail(json)
                    ?: throw Exception("解析视频信息失败")

                // 2. 准备输出文件
                val fileName = "douyin_${video.awemeId}_${System.currentTimeMillis()}.mp4"
                val outputFile = storageManager.getVideoOutputFile(fileName)

                // 3. 构建请求头
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...",
                    "Referer" to "https://www.douyin.com/"
                )

                // 4. 启动前台服务进行下载（不再阻塞 ViewModel 协程，进度由 Service 通知）
                val context = getApplication<android.app.Application>()
                DownloadService.start(context, video.videoUrl, outputFile.absolutePath, headers)

                // 状态更新交由 Service 完成后的回调（这里可以监听 Service 的广播或手动更新）
                // 简单的处理：暂时留在 Downloading 状态，下载完成通知由 Notification 展示。
                // 若需要 UI 状态同步，可后续通过 BroadcastReceiver 或事件总线完成。
            } catch (e: Exception) {
                _downloadState.value = DownloadUiState.Error(e.message ?: "下载失败")
            }
        }
    }

    private fun isCookieValid(cookieString: String): Boolean {
        // 检查关键字段是否存在
        return cookieString.contains("sessionid") || cookieString.contains("passport_csrf_token")
    }
}

sealed class LoginState {
    object Unknown : LoginState()
    object LoggedIn : LoginState()
    object NotLoggedIn : LoginState()
}