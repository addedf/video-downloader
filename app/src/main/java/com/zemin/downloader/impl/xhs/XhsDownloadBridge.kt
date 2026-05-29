package com.zemin.downloader.impl.xhs

import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.base.BasePyDownloadBridge
import com.zemin.downloader.common.bean.PythonDownloadResult
import com.zemin.downloader.impl.DownloadType

/**
 * 小红书python桥接器
 */
class XhsDownloadBridge : BasePyDownloadBridge() {
    override val type = DownloadType.XIAO_HONG_SHU

    override val pyModuleName: String = "xhs.cli.xhs_android_entry"

    override suspend fun handleDownloadResult(result: String): IDownloadResult {
        return PythonDownloadResult.fromJson(result)
    }
}