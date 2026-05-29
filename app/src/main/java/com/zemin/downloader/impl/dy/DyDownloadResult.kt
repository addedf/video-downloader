package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.ApiMetric
import com.zemin.downloader.common.DownloadMetric
import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.util.compactFileName
import com.zemin.downloader.common.util.ellipsizeMiddle
import com.zemin.downloader.common.util.formatBytes
import com.zemin.downloader.common.util.formatDuration
import com.zemin.downloader.common.util.formatOutputDir
import com.zemin.downloader.common.util.formatSpeed
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DyDownloadResult(
    override val ok: Boolean,
    override val message: String,
    override val error: String?,
    val outputDir: String?,
    override val files: List<String> = emptyList(),
    val success: Int,
    val failed: Int,
    override val skipped: Int,
    val timings: Map<String, Int>,
    val downloadMetrics: List<DownloadMetric>,
    val apiMetrics: List<ApiMetric>
) : IDownloadResult {
    override fun fromDownload(downloadResult: String) {
    }

    override fun formatDownloadSummary(
        mediaCount: Int,
        mediaRegisterMs: Int,
        taskTotalMs: Int,
    ): String {
        val outputDir = this.outputDir
        val files = this.files
        val visibleFiles = files.map(::File).filterNot { it.name == "download_manifest.jsonl" }
            .filterNot { it.extension.equals("json", ignoreCase = true) }
            .filterNot { it.extension.equals("jsonl", ignoreCase = true) }
            .map { compactFileName(it.name) }.distinct()

        return buildString {
            append("完成：")
            append(formatCounts())

            formatProcessLog(
                mediaRegisterMs = mediaRegisterMs,
                taskTotalMs = taskTotalMs,
            )?.let {
                append("\n")
                append(it)
            }

            if (mediaCount > 0) {
                append("\n下载目录：$mediaCount 个 · ")
                append(formatOutputDir(outputDir.orEmpty()))
            } else {
                outputDir?.takeIf { it.isNotBlank() }?.let {
                    append("\n下载目录：")
                    append(formatOutputDir(it))
                }
            }

            if (visibleFiles.isNotEmpty()) {
                append("\n文件名称：")
                append(visibleFiles.take(2).joinToString("、"))
                if (visibleFiles.size > 2) {
                    append(" 等 ${visibleFiles.size} 个")
                }
            }
        }
    }

    fun formatCounts(): String {
        val parts = mutableListOf("成功 ${this.success}")
        if (this.failed > 0) parts += "失败 ${this.failed}"
        if (this.skipped > 0) parts += "跳过 ${this.skipped}"
        return parts.joinToString(" / ")
    }

    fun formatProcessLog(
        mediaRegisterMs: Int,
        taskTotalMs: Int,
    ): String? {
        val metrics = this.downloadMetrics
        val apiMetrics = this.apiMetrics
        val timings = this.timings
        val primary = metrics.firstOrNull { it.ok && it.bytes > 0 } ?: return null
        val size = formatBytes(primary.bytes)
        val speed = formatSpeed(primary.speedKbps)
        val firstChunk = if (primary.firstChunkMs > 0) {
            "首包 ${formatDuration(primary.firstChunkMs)}"
        } else {
            null
        }
        val resolveCost = (timings["resolve_input_url_ms"] ?: 0) + (timings["parse_url_ms"] ?: 0)
        val detailCost = apiMetrics.filter { it.name == "get_video_detail" }.sumOf { it.durationMs }
            .takeIf { it > 0 } ?: apiMetrics.sumOf { it.durationMs }
        val transferCost = metrics.sumOf { it.durationMs }
        val downloadStage = (timings["download_content_ms"] ?: 0).coerceAtLeast(0)
        val otherCost = (downloadStage - detailCost - transferCost).coerceAtLeast(0)
        val host = (primary.finalHost ?: primary.host)?.let { ellipsizeMiddle(it, 24) }
        val firstLine = mutableListOf<String>()
        if (resolveCost > 0) firstLine += "解析 ${formatDuration(resolveCost)}"
        if (detailCost > 0) firstLine += "详情 ${formatDuration(detailCost)}"
        firstLine += "传输 ${formatDuration(transferCost)}"

        val secondLine = mutableListOf<String>()
        if (mediaRegisterMs > 0) secondLine += "入库 ${formatDuration(mediaRegisterMs)}"
        if (otherCost > 0) secondLine += "其他 ${formatDuration(otherCost)}"
        secondLine += "总计 ${formatDuration(taskTotalMs)}"

        val fileLine = mutableListOf("文件 $size", "均速 $speed")
        firstChunk?.let { fileLine += it }

        return buildString {
            append("耗时：")
            append(firstLine.joinToString(" · "))
            append("\n")
            append("      ")
            append(secondLine.joinToString(" · "))
            append("\n下载：")
            append(fileLine.joinToString(" · "))
            if (!host.isNullOrBlank()) {
                append("\nCDN：")
                append(host)
            }
        }
    }
}