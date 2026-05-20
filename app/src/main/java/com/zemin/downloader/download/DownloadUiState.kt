package com.zemin.downloader.download

import java.io.File

// 直接放在 DownloadViewModel.kt 文件末尾，或者新建一个 DownloadUiState.kt 文件
sealed class DownloadUiState {
    object Idle : DownloadUiState()
    object Downloading : DownloadUiState()
    data class Completed(val file: File) : DownloadUiState()
    data class Error(val message: String) : DownloadUiState()
}