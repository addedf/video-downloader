package com.zemin.downloader.common

/**
 * @author maozemin@coocaa.com
 * @desc
 */
interface IStoreModule: IBaseBusinessModule {
    fun hasCookie(): Boolean

    fun getCookieString(): String?
}