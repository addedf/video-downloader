package com.zemin.downloader.common.base

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.zemin.downloader.appContext
import com.zemin.downloader.common.IDownloadBridge
import com.zemin.downloader.common.IDownloadResult
import com.zemin.downloader.common.PyBridgeConfig
import com.zemin.downloader.common.util.LocalStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
const val KEY_WARM_UP = "warm_up"
const val KEY_REFRESH_COOKIES = "refresh_cookies"
const val KEY_DOWNLOAD = "download"

abstract class BasePyDownloadBridge : IDownloadBridge {
    override val python: Python
        get() {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            return Python.getInstance()
        }

    override val pyBridgeConfig: PyBridgeConfig
        get() = PyBridgeConfig(
            cookieString = LocalStorage.getCookieString(type.name)
        )


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

    abstract suspend fun handleDownloadResult(result: String): IDownloadResult
}


