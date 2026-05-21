package com.zemin.downloader.core

import org.json.JSONObject

object VideoParser {

    fun parseAwemeDetail(jsonString: String): DouyinVideo? {
        return try {
            val root = JSONObject(jsonString)
            val awemeDetail = root.optJSONObject("aweme_detail") ?: return null
            val video = awemeDetail.optJSONObject("video") ?: return null
            val bestUrl = findBestVideoUrl(video).orEmpty()

            val author = awemeDetail.optJSONObject("author")
            DouyinVideo(
                awemeId = awemeDetail.optString("aweme_id"),
                desc = awemeDetail.optString("desc"),
                authorName = author?.optString("nickname").orEmpty(),
                authorId = author?.optString("sec_uid").orEmpty(),
                videoUrl = bestUrl,
                coverUrl = video.optJSONObject("cover")
                    ?.optJSONArray("url_list")
                    ?.optString(0)
                    .orEmpty(),
                duration = video.optLong("duration", 0L)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun findBestVideoUrl(video: JSONObject): String? {
        val bitRateArray = video.optJSONArray("bit_rate")
        var bestUrl: String? = null
        var bestBitrate = -1

        if (bitRateArray != null) {
            for (i in 0 until bitRateArray.length()) {
                val item = bitRateArray.optJSONObject(i) ?: continue
                if (item.optInt("is_watermark", 0) == 1) continue

                val bitrate = item.optInt("bit_rate", 0)
                val url = item.optJSONObject("play_addr")
                    ?.optJSONArray("url_list")
                    ?.optString(0)

                if (!url.isNullOrBlank() && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = url
                }
            }
        }

        if (!bestUrl.isNullOrBlank()) return bestUrl

        return video.optJSONObject("play_addr")
            ?.optJSONArray("url_list")
            ?.optString(0)
            ?.takeIf { it.isNotBlank() }
    }
}
