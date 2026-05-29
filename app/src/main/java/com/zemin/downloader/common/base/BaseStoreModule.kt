package com.zemin.downloader.common.base

import com.zemin.downloader.common.IStoreModule
import com.zemin.downloader.common.util.LocalStorage

/**
 * @author maozemin@coocaa.com
 * @desc
 */
abstract class BaseStoreModule : IStoreModule {
    override fun hasCookie(): Boolean {
        return !getCookieString().isNullOrEmpty()
    }

    override fun getCookieString(): String? {
        return LocalStorage.getCookieString(downloadType.type)
    }
}