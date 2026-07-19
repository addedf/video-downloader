package com.zemin.downloader.common.bean

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class PyResolveResponse(
    val ok: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    @Json(name = "source_url") val sourceUrl: String? = null,
    @Json(name = "source_id") val sourceId: String? = null,
    val title: String? = null,
    val author: String? = null,
    @Json(name = "cover_url") val coverUrl: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    val resources: List<PyResolveResourceResponse> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class PyResolveResourceResponse(
    val title: String = "",
    @Json(name = "media_type") val mediaType: String = "",
    @Json(name = "download_urls") val downloadUrls: List<String> = emptyList(),
    val selected: Boolean = true,
)
