package com.zemin.downloader.common.base

import androidx.annotation.WorkerThread
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zemin.downloader.appContext
import com.zemin.downloader.common.IDownloadModule
import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.PyBridgeConfig
import com.zemin.downloader.common.core.StoreModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
const val KEY_WARM_UP = "warm_up"
const val KEY_REFRESH_COOKIES = "refresh_cookies"
const val KEY_DOWNLOAD = "download"

abstract class BasePyDownloadModule : IDownloadModule {
    override val python: Python
        get() {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            return Python.getInstance()
        }

    override val pyBridgeConfig: PyBridgeConfig
        get() = PyBridgeConfig(cookieString = StoreModule.getCookieString())

    /**
     * 处理下载结果
     */
    @WorkerThread
    abstract suspend fun handleDownloadResult(result: String): IDownloadResult

    override suspend fun warmUp() = withContext(Dispatchers.IO) {
        python.getModule(pyModuleName).callAttr(
            KEY_WARM_UP,
            pyBridgeConfig.appDir,
            pyBridgeConfig.outDownloadDir,
            pyBridgeConfig.cookieString
        )
    }

    override suspend fun refreshCookies(cookieString: String) = withContext(Dispatchers.IO) {
        python.getModule(pyModuleName).callAttr(KEY_REFRESH_COOKIES, cookieString)
    }

    override suspend fun download(inputText: String): IDownloadResult =
        withContext(Dispatchers.IO) {
            python.getModule(pyModuleName).callAttr(KEY_DOWNLOAD, inputText).toString().let {
                handleDownloadResult(it)
            }
        }
}


