package com.zemin.downloader.common.core

import android.util.Log
import com.zemin.downloader.common.IBridgeAbility
import com.zemin.downloader.common.util.LocalStorage
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.impl.dy.DyBridgeAbility
import com.zemin.downloader.impl.xhs.XhsBridgeAbility
import java.util.concurrent.ConcurrentHashMap

object BridgeAbilityManager {
    private const val TAG = "BridgeAbilityManager"
    private val abilityCache = ConcurrentHashMap<DownloadType, IBridgeAbility>()
    internal var currentAbility: IBridgeAbility = DyBridgeAbility()
        private set

    suspend fun init() {
        currentAbility = getOrCreateBridgeAbility(LocalStorage.getAbility())
    }

    suspend fun update(downloadType: DownloadType) {
        if (currentAbility.downloadType == downloadType) {
            Log.d(TAG, "update: is same downloadType = $downloadType")
            return
        }

        Log.d(TAG, "update: downloadType = $downloadType")
        currentAbility = getOrCreateBridgeAbility(downloadType)
        LocalStorage.saveAbility(downloadType)
        notifyAbilityChanged()
    }

    private fun notifyAbilityChanged() {

    }

    private suspend fun getOrCreateBridgeAbility(downloadType: DownloadType): IBridgeAbility {
        val cacheAbility = abilityCache[downloadType]
        if (cacheAbility != null) {
            Log.d(TAG, "getBridgeAbility: from cache, type = $downloadType")
            return cacheAbility
        }

        val ability = when (downloadType) {
            DownloadType.DOU_YIN -> DyBridgeAbility()
            DownloadType.XIAO_HONG_SHU -> XhsBridgeAbility()
        }
        abilityCache[downloadType] = ability
        // 初始化
        val initSuccess = ability.init()
        Log.d(TAG, "createBridgeAbility: initSuccess = $initSuccess, downloadType = $downloadType")
        return ability
    }
}