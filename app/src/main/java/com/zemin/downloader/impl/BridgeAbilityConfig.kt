package com.zemin.downloader.impl

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
object BridgeAbilityConfig {

    fun getDefaultDownloadType() = DownloadType.DOU_YIN

    fun getAllAbility(): List<DownloadType> {
        return DownloadType.entries
    }
}