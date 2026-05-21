// ui/DownloadViewModel.kt
package com.zemin.downloader.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zemin.downloader.core.CookieStorage
import com.zemin.downloader.core.DouyinApiClient
import com.zemin.downloader.core.DownloadEngine
import com.zemin.downloader.core.DownloadProgress
import com.zemin.downloader.core.SignatureProvider
import com.zemin.downloader.core.StorageManager
import com.zemin.downloader.core.VideoParser
import com.zemin.downloader.download.DownloadUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val signatureProvider = SignatureProvider(application).also {
        viewModelScope.launch { it.preload() }
    }
    private val cookieStorage = CookieStorage(application)
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
        // 预加载签名 JS
        viewModelScope.launch {
            signatureProvider.preload()
        }
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

    fun downloadVideo(awemeId: String) {
        viewModelScope.launch {
            val cookiesMap = cookieStorage.getCookiesMap()
            if (cookiesMap.isEmpty()) {
                _downloadState.value = DownloadUiState.Error("请先登录")
                return@launch
            }

            // 检查存储权限 (Android 10 以下)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val context = getApplication<Application>()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                    _downloadState.value = DownloadUiState.Error("缺少存储权限")
                    return@launch
                }
            }

            _downloadState.value = DownloadUiState.Downloading
            val apiClient = DouyinApiClient(cookiesMap, signatureProvider)

            try {
                // 1. 获取视频详情
                val json = apiClient.requestAwemeDetail(awemeId)
                if (json == null) {
                    _downloadState.value = DownloadUiState.Error("获取视频信息失败")
                    return@launch
                }

                // 2. 解析视频信息
                val video = VideoParser.parseAwemeDetail(json)
                if (video == null) {
                    _downloadState.value = DownloadUiState.Error("解析视频信息失败")
                    return@launch
                }

                // 3. 准备输出文件
                val fileName = "douyin_${video.awemeId}_${System.currentTimeMillis()}.mp4"
                val outputFile = storageManager.getVideoOutputFile(fileName)

                // 4. 下载视频
                val downloadRequest = apiClient.buildVideoDownloadRequest(json)
                if (downloadRequest == null) {
                    _downloadState.value = DownloadUiState.Error("构造视频下载地址失败")
                    return@launch
                }
                DownloadEngine(apiClient.downloadClient()).downloadFile(
                    downloadRequest.url,
                    outputFile,
                    downloadRequest.headers
                ).collect { progress ->
                    _progressEvents.emit(progress)
                    when (progress) {
                        is DownloadProgress.Success -> {
                            storageManager.registerToMediaStore(progress.file)
                            _downloadState.value = DownloadUiState.Completed(progress.file)
                        }
                        is DownloadProgress.Error -> _downloadState.value = DownloadUiState.Error(progress.exception.message ?: "下载失败")
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                _downloadState.value = DownloadUiState.Error(e.message ?: "未知错误")
            }
        }
    }

    private fun isCookieValid(cookieString: String): Boolean {
        return cookieString.contains("sessionid") || cookieString.contains("passport_csrf_token")
    }

    // 短链接解析
    private suspend fun resolveShortUrl(shortUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().followRedirects(false).build()
                val response = client.newCall(Request.Builder().url(shortUrl).build()).execute()
                response.header("Location")
            } catch (e: Exception) {
                null
            }
        }
    }
}

sealed class LoginState {
    object Unknown : LoginState()
    object LoggedIn : LoginState()
    object NotLoggedIn : LoginState()
}
