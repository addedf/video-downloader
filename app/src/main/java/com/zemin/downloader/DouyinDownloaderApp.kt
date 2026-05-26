package com.zemin.downloader

import android.app.Application
import android.content.Context
import com.zemin.downloader.core.PythonDownloadBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DouyinDownloaderApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        lateinit var instance: DouyinDownloaderApp
            private set

        val appContext: Context
            get() = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        warmUpPython()
    }

    private fun warmUpPython() {
        appScope.launch {
            runCatching {
                PythonDownloadBridge.warmUp()
            }
        }
    }
}
