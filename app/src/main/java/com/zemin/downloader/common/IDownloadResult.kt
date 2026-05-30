package com.zemin.downloader.common

import com.zemin.downloader.common.bean.ApiMetric
import com.zemin.downloader.common.bean.DownloadMetric

interface IDownloadResult

open class PyDownloadResult(
    val ok: Boolean,
    val message: String,
    val error: String?,
    val outputDir: String?,
    val files: List<String> = emptyList(),
    val success: Int,
    val failed: Int,
    val skipped: Int,
    val timings: Map<String, Int>,
    val downloadMetrics: List<DownloadMetric>,
    val apiMetrics: List<ApiMetric>
) : IDownloadResult