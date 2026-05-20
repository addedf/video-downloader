// parser/VideoParser.kt
package com.zemin.downloader.core

import org.json.JSONObject

object VideoParser {

    /**
     * 从 aweme_detail 接口返回的 JSON 中提取视频信息
     * 规则：选择 video.bit_rate 中 watermark=0 且码率最高的 play_addr 地址
     */
    fun parseAwemeDetail(jsonString: String): DouyinVideo? {
        return try {
            val root = JSONObject(jsonString)
            val awemeDetail = root.getJSONObject("aweme_detail")
            val video = awemeDetail.getJSONObject("video")
            val bitRateArray = video.getJSONArray("bit_rate")

            var bestUrl: String? = null
            var bestBitrate = 0

            for (i in 0 until bitRateArray.length()) {
                val item = bitRateArray.getJSONObject(i)
                // 跳过水印标记（如果存在 is_watermark 字段）
                if (item.optInt("is_watermark", 0) == 1) continue

                val bitrate = item.getInt("bit_rate")
                if (bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    val playAddr = item.getJSONObject("play_addr")
                    // url_list 是数组，取第一个作为主地址
                    val urlList = playAddr.getJSONArray("url_list")
                    bestUrl = urlList.getString(0)
                }
            }

            if (bestUrl == null) return null

            // 基础信息
            val awemeId = awemeDetail.getString("aweme_id")
            val desc = awemeDetail.optString("desc", "")
            val author = awemeDetail.getJSONObject("author")
            val authorName = author.getString("nickname")
            val authorId = author.getString("sec_uid")
            val coverUrl = awemeDetail.getJSONObject("video")
                .getJSONObject("cover").getJSONArray("url_list").getString(0)
            val duration = video.getLong("duration")

            DouyinVideo(
                awemeId = awemeId,
                desc = desc,
                authorName = authorName,
                authorId = authorId,
                videoUrl = bestUrl,
                coverUrl = coverUrl,
                duration = duration
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}