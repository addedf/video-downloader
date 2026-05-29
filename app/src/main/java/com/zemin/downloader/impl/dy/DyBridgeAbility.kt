package com.zemin.downloader.impl.dy

import com.zemin.downloader.common.IBridgeAbility

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
class DyBridgeAbility : IBridgeAbility {
    override val loginModule = DyLoginModule()

    override val downloadBridge = DyDownloadBridge()
}