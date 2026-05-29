package com.zemin.downloader.common

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.zemin.downloader.common.util.MediaStorageManager
import com.zemin.downloader.impl.DownloadType

/**
 * @author maozemin@coocaa.com
 * @desc: 通用下载桥接接口
 */
interface IDownloadResult

interface IDownloadBridge {
    val type: DownloadType

    val python: Python

    val pyModuleName: String

    val pyBridgeConfig: PyBridgeConfig

    suspend fun warmUp(): PyObject?

    suspend fun download(inputText: String): IDownloadResult

    suspend fun refreshCookies(cookieString: String): PyObject?
}

data class PyBridgeConfig(val cookieString: String?) {
    val appDir: String get() = MediaStorageManager.getAppFileDir().absolutePath

    val outDownloadDir: String get() = MediaStorageManager.getPythonDownloadDir().absolutePath
}

