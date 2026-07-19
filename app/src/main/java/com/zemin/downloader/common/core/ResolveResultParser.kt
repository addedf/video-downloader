package com.zemin.downloader.common.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.zemin.downloader.R
import com.zemin.downloader.appContext
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.bean.PyResolveResponse

object ResolveResultParser {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(PyResolveResponse::class.java)

    fun parse(raw: String): PyResolveResult {
        val response = adapter.fromJson(raw) ?: PyResolveResponse()
        val error = response.error?.takeIf { it.isNotBlank() }
        return PyResolveResult(
            ok = response.ok,
            message = response.message?.takeIf { it.isNotBlank() } ?: error ?: appContext.getString(
                R.string.main_task_complete
            ),
            error = error,
            sourceUrl = response.sourceUrl,
            sourceId = response.sourceId,
            title = response.title,
            author = response.author,
            coverUrl = response.coverUrl,
            mediaType = response.mediaType,
            resources = response.resources.map {
                ResolvedResource(
                    title = it.title,
                    mediaType = it.mediaType,
                    downloadUrls = it.downloadUrls,
                    selected = it.selected,
                )
            },
        )
    }
}
