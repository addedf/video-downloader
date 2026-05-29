package com.zemin.downloader

import android.app.Application
import android.content.Context
import com.zemin.downloader.impl.dy.DyDownloadBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
val appContext: Context get() = DownloaderApp.instance.applicationContext

class DownloaderApp : Application() {

    companion object {
        lateinit var instance: DownloaderApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        warmUpPython()
    }

    private fun warmUpPython() {
        appScope.launch {
            runCatching {
                DyDownloadBridge.warmUp()
            }
        }
    }
}
