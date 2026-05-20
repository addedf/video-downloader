package com.zemin.downloader.core

data class DouyinVideo(
    val awemeId: String,
    val desc: String,
    val authorName: String,
    val authorId: String,
    val videoUrl: String,          // 无水印最高码率地址
    val coverUrl: String,
    val duration: Long
)