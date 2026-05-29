package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.base.BasePyDownloadBridge
import com.zemin.downloader.common.bean.PythonDownloadResult
import com.zemin.downloader.impl.DownloadType

/**
 * 抖音python桥接器
 */
class DyDownloadBridge : BasePyDownloadBridge() {
    override val type = DownloadType.DOU_YIN

    override val pyModuleName: String = "dy.cli.dy_android_entry"

    override suspend fun handleDownloadResult(result: String): IDownloadResult {
        return PythonDownloadResult.fromJson(result)
    }
}