package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.base.BasePyDownloadModule
import com.zemin.downloader.common.core.DownloadResultParser

/**
 * 抖音python桥接器
 */
class DyDownloadModule : BasePyDownloadModule() {
    override val pyModuleName: String = "dy.cli.dy_android_entry"

    override suspend fun handleDownloadResult(result: String): IDownloadResult {
        return DownloadResultParser.fromJson(result)
    }
}