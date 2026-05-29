package com.zemin.downloader.common

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
interface IBridgeAbility {
    val loginModule: ILoginModule

    val downloadBridge: IDownloadBridge
}