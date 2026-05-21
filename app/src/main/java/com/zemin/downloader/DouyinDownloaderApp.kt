package com.zemin.downloader

import android.app.Application
import com.zemin.downloader.core.PythonDownloadBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DouyinDownloaderApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        warmUpPython()
    }

    private fun warmUpPython() {
        appScope.launch {
            runCatching {
                PythonDownloadBridge(this@DouyinDownloaderApp).warmUp()
            }
        }
    }
}
