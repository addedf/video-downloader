package com.zemin.downloader.impl.xhs

import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.base.BasePyDownloadModule
import com.zemin.downloader.common.core.DownloadResultParser

/**
 * 小红书python桥接器
 */
class XhsDownloadModule : BasePyDownloadModule() {
    override val pyModuleName: String = "xhs.cli.xhs_android_entry"

    override suspend fun handleDownloadResult(result: String): IDownloadResult {
        return DownloadResultParser.fromJson(result)
    }
}