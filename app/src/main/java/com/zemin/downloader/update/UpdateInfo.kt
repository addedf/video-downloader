package com.zemin.downloader.update

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val forceUpdate: Boolean,
) {
    fun canUpdate(currentVersionCode: Long): Boolean {
        return versionCode > currentVersionCode && apkUrl.isNotBlank()
    }

    companion object {
        val EMPTY = UpdateInfo(
            versionCode = 0L,
            versionName = "",
            apkUrl = "",
            changelog = "",
            forceUpdate = false,
        )
    }
}
