package com.zemin.downloader.common

interface IDownloadResult {
    val ok: Boolean

    val files: List<String>

    val skipped: Int

    val message: String?

    val error: String?

    fun fromDownload(downloadResult: String)

    fun formatDownloadSummary(mediaCount: Int, mediaRegisterMs: Int, taskTotalMs: Int): String
}

data class ApiMetric(
    val name: String, val durationMs: Int
)

data class DownloadMetric(
    val ok: Boolean,
    val host: String?,
    val finalHost: String?,
    val bytes: Long,
    val durationMs: Int,
    val firstChunkMs: Int,
    val speedKbps: Int
)
