package com.zemin.downloader.impl

/**
 * @author maozemin@coocaa.com
 * @desc:
 */

const val TYPE_DOU_YIN = "Douyin"
const val TYPE_XHS = "Xhs"

fun getDefaultDownloadType() = DownloadType.DOU_YIN

enum class DownloadType(val type: String) {
    DOU_YIN(TYPE_DOU_YIN), XIAO_HONG_SHU(TYPE_XHS);

    companion object {
        fun fromType(type: String?): DownloadType {
            return entries.find { it.type == type } ?: getDefaultDownloadType()
        }
    }
}

