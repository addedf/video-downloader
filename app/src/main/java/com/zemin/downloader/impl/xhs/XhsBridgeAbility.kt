package com.zemin.downloader.impl.xhs

import com.zemin.downloader.common.IBridgeAbility

/**
 * @author maozemin@coocaa.com
 * @desc:
 */
class XhsBridgeAbility : IBridgeAbility {
    override val loginModule = XhsLoginModule()

    override val downloadBridge = XhsDownloadBridge()
}