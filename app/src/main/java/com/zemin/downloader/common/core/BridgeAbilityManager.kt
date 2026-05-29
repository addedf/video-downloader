package com.zemin.downloader.common.core

import com.zemin.downloader.common.IBridgeAbility
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.impl.dy.DyBridgeAbility

object BridgeAbilityManager {
    private var currentAbility: IBridgeAbility = DyBridgeAbility()

    fun init(downloadType: DownloadType) {
    }


    fun update(bridgeAbility: IBridgeAbility) {
        currentAbility = bridgeAbility
    }
}