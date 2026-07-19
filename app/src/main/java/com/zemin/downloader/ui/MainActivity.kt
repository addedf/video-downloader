package com.zemin.downloader.ui

import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.webkit.CookieManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.common.DownloadProgressListener
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.LoginModule
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.common.core.currentType
import com.zemin.downloader.common.util.DownloadHistoryRecord
import com.zemin.downloader.common.util.DownloadHistoryStore
import com.zemin.downloader.common.util.formatBytes
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.ActivityMainBinding
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.util.PlatformResolver
import com.zemin.downloader.ui.util.extractSharedText
import com.zemin.downloader.ui.view.DyActionButton
import com.zemin.downloader.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshLoginState()
    }
    private var isDownloading = false
    private var currentPreview: PyResolveResult? = null
    private var currentPreviewInput: String? = null
    private var suppressInputChangeHandling = false
    private var lastClipboardPromptUrl: String? = null
    private var pendingClipboardInput: String? = null
    private var selectedPreviewMode: PreviewMode = PreviewMode.IMAGE
    private var selectedPreviewIndex = 0
    private var progressDetailMessage = ""
    private var systemInsetTop = 0
    private var systemInsetBottom = 0
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var progressBubblePositioned = false
    private val lastProgressUiUpdatedAt = AtomicLong(PROGRESS_RECORD_INIT_TIME)
    private val previewImageLoadToken = AtomicLong(PREVIEW_LOAD_INIT_TOKEN)
    private val updateManager by lazy { UpdateManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupProgressBubble()
        readSharedText(intent)
        updateManager.checkOnStart()

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        binding.btnLogin.setOnLongClickListener {
            clearDouyinCookies()
            true
        }
        binding.btnDownload.setOnClickListener {
            val input = binding.etUrl.text.toString().trim()
            when {
                input.isEmpty() -> toast(getString(R.string.main_toast_empty_input, currentTitle))
                isDownloading -> toast(getString(R.string.main_toast_task_running))
                else -> resolveAndRenderPreview(input)
            }
        }
        binding.btnClear.setOnClickListener {
            clearLinkAndCancelDownload()
        }
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressInputChangeHandling) return
                val input = s?.toString()?.trim().orEmpty()
                if (input != currentPreviewInput) clearPreview()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.btnHistoryOpen.setOnClickListener {
            openLatestHistoryFile()
        }
        binding.btnHistoryShare.setOnClickListener {
            shareLatestHistoryFile()
        }
        binding.btnHistoryRetry.setOnClickListener {
            retryLatestHistory()
        }
        binding.btnHistoryClear.setOnClickListener {
            DownloadHistoryStore.clear()
            refreshHistoryUi()
            toast(getString(R.string.main_toast_history_cleared))
        }
        binding.btnImageTab.setOnClickListener { selectPreviewMode(PreviewMode.IMAGE) }
        binding.btnVideoTab.setOnClickListener { selectPreviewMode(PreviewMode.VIDEO) }
        binding.btnCopyLink.setOnClickListener { copyCurrentPreviewLink() }
        binding.btnSaveSheet.setOnClickListener { saveCurrentPreview() }
        binding.btnMine.setOnClickListener { showMineSheet() }
        binding.btnCloseMineSheet.setOnClickListener { hideSheets() }
        binding.sheetMask.setOnClickListener { hideSheets() }
        binding.mineSheet.setOnClickListener { }
        binding.dialogMask.setOnClickListener { hideClipboardDialog() }
        binding.clipboardDialogPanel.setOnClickListener { }
        binding.btnClipboardDismiss.setOnClickListener { hideClipboardDialog() }
        binding.btnClipboardParse.setOnClickListener {
            val input = pendingClipboardInput.orEmpty()
            hideClipboardDialog()
            if (input.isNotBlank()) {
                setInputText(input)
                clearPreview()
                resolveAndRenderPreview(input)
            }
        }
        styleActionButtons()
        refreshHistoryUi()
        refreshActionButton()
    }

    override fun onResume() {
        super.onResume()
        scheduleClipboardCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.videoPreview.stopPlayback()
        hideClipboardDialog()
    }

    private fun resolveAndRenderPreview(inputText: String) {
        val result = PlatformResolver.resolve(inputText)
        if (result == null) {
            showUnsupportedLink()
            return
        }

        lifecycleScope.launch {
            if (currentDownloadType != result.downloadType) {
                BridgeAbilityManager.update(result.downloadType)
            }
            if (!ensureLoggedIn()) return@launch

            setUiEnabled(false)
            showIndeterminateProgress(getString(R.string.main_progress_resolving))
            clearPreview()
            try {
                val preview = DownloadModule.resolve(result.normalizedInput)
                if (!preview.ok) {
                    showError(preview.error ?: preview.message)
                    return@launch
                }
                setInputText(result.normalizedInput)
                renderPreview(result.normalizedInput, preview)
            } catch (e: Exception) {
                showError(
                    getString(
                        R.string.main_error_exception,
                        e.message ?: getString(R.string.main_error_unknown)
                    )
                )
            } finally {
                setUiEnabled(true)
                binding.progressBubble.hide()
            }
        }
    }

    private fun ensureLoggedIn(): Boolean {
        if (!LoginModule.needLogin) return true
        val loggedIn = LoginModule.isLoggedIn(StoreModule.getCookieString().orEmpty())
        if (loggedIn) return true

        showError(getString(R.string.main_toast_need_login, currentTitle))
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
        return false
    }

    private fun startDownload(shareText: String, preview: PyResolveResult? = null) {
        if (!ensureLoggedIn()) return

        isDownloading = true
        setUiEnabled(false)
        showIndeterminateProgress(getString(R.string.main_progress_preparing))
        lastProgressUiUpdatedAt.set(PROGRESS_RECORD_INIT_TIME)

        lifecycleScope.launch {
            val taskStartedAt = System.currentTimeMillis()
            val historySourceUrl = preview?.sourceUrl ?: shareText
            val historyTitle = preview?.title?.takeIf { it.isNotBlank() } ?: getString(
                R.string.main_input_title_douyin
            )
            val historyMediaType = preview?.mediaType ?: currentType
            try {
                StoreModule.cleanupDownloadCache()
                val result = DownloadModule.download(
                    inputText = shareText,
                    progressListener = createDownloadProgressListener(),
                )

                binding.progressBubble.showProgress(
                    PROGRESS_COMPLETE,
                    getString(R.string.main_status_download_done),
                )
                progressDetailMessage = getString(R.string.main_status_download_done)

                val registeredUris = result.files.map(::File).mapNotNull { file ->
                    StoreModule.registerMediaFile(file)?.also {
                        StoreModule.deleteTemporaryDownloadFile(file)
                    }
                }

                if (result.ok || result.skipped > 0) {
                    StoreModule.cleanupDownloadSidecars()
                    saveDownloadHistory(
                        sourceUrl = historySourceUrl,
                        title = historyTitle,
                        mediaType = historyMediaType,
                        status = DownloadHistoryRecord.STATUS_SUCCESS,
                        savedUris = registeredUris,
                        errorMessage = null,
                        createdAt = taskStartedAt,
                    )
                    toast(getString(R.string.main_toast_download_done))
                } else {
                    val errorMessage = result.error ?: result.message
                    saveDownloadHistory(
                        sourceUrl = historySourceUrl,
                        title = historyTitle,
                        mediaType = historyMediaType,
                        status = DownloadHistoryRecord.STATUS_FAILED,
                        savedUris = emptyList(),
                        errorMessage = errorMessage,
                        createdAt = taskStartedAt,
                    )
                    showError(errorMessage)
                }
            } catch (e: Exception) {
                val errorMessage = getString(
                    R.string.main_error_exception,
                    e.message ?: getString(R.string.main_error_unknown)
                )
                saveDownloadHistory(
                    sourceUrl = historySourceUrl,
                    title = historyTitle,
                    mediaType = historyMediaType,
                    status = DownloadHistoryRecord.STATUS_FAILED,
                    savedUris = emptyList(),
                    errorMessage = errorMessage,
                    createdAt = taskStartedAt,
                )
                showError(errorMessage)
            } finally {
                isDownloading = false
                setUiEnabled(true)
                refreshHistoryUi()
                lifecycleScope.launch {
                    delay(PROGRESS_HIDE_DELAY_MS)
                    binding.progressBubble.hide()
                }
            }
        }
    }

    private fun renderPreview(inputText: String, preview: PyResolveResult) {
        currentPreview = preview
        currentPreviewInput = inputText

        val mediaTypeText = formatMediaType(preview.mediaType)
        val selectedResources = preview.resources.filter { it.selected }.ifEmpty { preview.resources }
        val selectedResourceCount = selectedResources.size
        val title = preview.title.orEmpty().ifBlank { getString(R.string.main_preview_title_fallback) }
        val author = preview.author.orEmpty().ifBlank { currentTitle }
        val imageCount = countResources(preview, PreviewMode.IMAGE)
        val videoCount = countResources(preview, PreviewMode.VIDEO)

        binding.previewSection.visibility = View.VISIBLE
        binding.tvPreviewTitle.text = title
        binding.tvPreviewMeta.text = getString(
            R.string.main_preview_meta_format,
            author,
            mediaTypeText,
            selectedResourceCount,
        )
        binding.btnImageTab.text = getString(R.string.main_preview_tab_images, imageCount)
        binding.btnVideoTab.text = getString(R.string.main_preview_tab_video, videoCount)
        binding.btnImageTab.visibility = if (imageCount > 0) View.VISIBLE else View.GONE
        binding.btnVideoTab.visibility = if (videoCount > 0) View.VISIBLE else View.GONE
        refreshActionButton()
        selectedPreviewMode = when {
            imageCount > 0 -> PreviewMode.IMAGE
            videoCount > 0 -> PreviewMode.VIDEO
            else -> PreviewMode.IMAGE
        }
        selectPreviewMode(selectedPreviewMode)
    }

    private fun clearPreview() {
        currentPreview = null
        currentPreviewInput = null
        previewImageLoadToken.incrementAndGet()
        binding.previewSection.visibility = View.GONE
        binding.ivPreviewCover.setImageDrawable(null)
        binding.ivPreviewCover.visibility = View.GONE
        binding.videoPreview.stopPlayback()
        binding.videoPreview.visibility = View.GONE
        binding.thumbContainer.removeAllViews()
        selectedPreviewIndex = 0
        hideSheets()
        refreshActionButton()
    }

    private fun refreshActionButton() {
        binding.btnDownload.text = getString(R.string.main_button_download)
    }

    private fun selectPreviewMode(mode: PreviewMode) {
        val preview = currentPreview ?: return
        val resources = resourcesForMode(preview, mode)
        if (resources.isEmpty()) return

        selectedPreviewMode = mode
        binding.btnImageTab.isSelected = mode == PreviewMode.IMAGE
        binding.btnVideoTab.isSelected = mode == PreviewMode.VIDEO
        binding.tvPlayIcon.visibility = View.GONE

        val first = resources.first()
        binding.tvPreviewCounter.text = when (mode) {
            PreviewMode.IMAGE -> if (first.mediaType == "cover") {
                getString(R.string.main_preview_counter_cover)
            } else {
                getString(R.string.main_preview_counter_image_format, 1, resources.size)
            }
            PreviewMode.VIDEO -> getString(R.string.main_preview_counter_video_format, 1, resources.size)
        }
        binding.tvPreviewItemTitle.text = when (mode) {
            PreviewMode.IMAGE -> if (first.mediaType == "cover") {
                getString(R.string.main_preview_item_cover)
            } else {
                getString(R.string.main_preview_item_image_format, 1)
            }
            PreviewMode.VIDEO -> getString(R.string.main_preview_item_video)
        }
        selectedPreviewIndex = 0
        renderThumbnails(resources, mode)
        updatePreviewResource(0, resources, mode)
    }

    private fun renderThumbnails(resources: List<ResolvedResource>, mode: PreviewMode) {
        binding.thumbContainer.removeAllViews()
        resources.forEachIndexed { index, resource ->
            val thumb = FrameLayout(this).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(62),
                ).also {
                    if (index > 0) it.topMargin = dp(5)
                }
                setBackgroundResource(
                    if (index == selectedPreviewIndex) R.drawable.bg_thumb_selected
                    else R.drawable.bg_thumb
                )
                setOnClickListener {
                    updatePreviewResource(index, resources, mode)
                }
            }
            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val fallback = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                text = when (resource.mediaType) {
                    "video" -> "▶"
                    "cover" -> "封面"
                    else -> (index + 1).toString()
                }
            }
            thumb.addView(imageView)
            thumb.addView(fallback)
            binding.thumbContainer.addView(thumb)
            val thumbUrl = thumbnailUrl(resource, currentPreview)
            if (thumbUrl.isNotBlank()) {
                loadBitmapInto(thumbUrl, imageView, fallback, PREVIEW_THUMB_CONNECT_TIMEOUT_MS, PREVIEW_THUMB_READ_TIMEOUT_MS)
            }
        }
    }

    private fun updatePreviewResource(
        index: Int,
        resources: List<ResolvedResource>,
        mode: PreviewMode,
    ) {
        val preview = currentPreview ?: return
        val resource = resources.getOrNull(index) ?: return
        selectedPreviewIndex = index
        updateThumbnailSelection(index)
        binding.tvPreviewCounter.text = when (mode) {
            PreviewMode.IMAGE -> if (resource.mediaType == "cover") {
                getString(R.string.main_preview_counter_cover)
            } else {
                getString(R.string.main_preview_counter_image_format, index + 1, resources.size)
            }
            PreviewMode.VIDEO -> getString(R.string.main_preview_counter_video_format, index + 1, resources.size)
        }
        binding.tvPreviewItemTitle.text = when (resource.mediaType) {
            "video" -> getString(R.string.main_preview_item_video)
            "cover" -> getString(R.string.main_preview_item_cover)
            else -> getString(R.string.main_preview_item_image_format, index + 1)
        }
        if (mode == PreviewMode.VIDEO) {
            playPreviewVideo(resource)
        } else {
            stopPreviewVideo()
            loadPreviewImage(preview, resource)
        }
    }

    private fun setInputText(text: String) {
        suppressInputChangeHandling = true
        binding.etUrl.setText(text)
        binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        suppressInputChangeHandling = false
    }

    private fun formatMediaType(mediaType: String?): String = when (mediaType) {
        "video" -> getString(R.string.main_media_type_video)
        "gallery" -> getString(R.string.main_media_type_gallery)
        else -> getString(R.string.main_media_type_unknown)
    }

    private fun loadPreviewImage(preview: PyResolveResult, resource: ResolvedResource? = null) {
        val imageUrl = thumbnailUrl(resource, preview)
        if (imageUrl.isBlank()) {
            binding.ivPreviewCover.visibility = View.GONE
            return
        }

        val token = previewImageLoadToken.incrementAndGet()
        binding.ivPreviewCover.visibility = View.VISIBLE
        binding.ivPreviewCover.setImageDrawable(null)
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = fetchBitmap(
                imageUrl,
                PREVIEW_IMAGE_CONNECT_TIMEOUT_MS,
                PREVIEW_IMAGE_READ_TIMEOUT_MS,
            )

            launch(Dispatchers.Main) {
                if (token != previewImageLoadToken.get()) return@launch
                if (bitmap != null) {
                    binding.ivPreviewCover.setImageBitmap(bitmap)
                    binding.ivPreviewCover.visibility = View.VISIBLE
                } else {
                    binding.ivPreviewCover.visibility = View.GONE
                }
            }
        }
    }

    private fun playPreviewVideo(resource: ResolvedResource) {
        val videoUrl = resource.downloadUrls.firstOrNull().orEmpty()
        binding.ivPreviewCover.visibility = View.GONE
        if (videoUrl.isBlank()) {
            stopPreviewVideo()
            binding.tvPlayIcon.visibility = View.VISIBLE
            return
        }
        previewImageLoadToken.incrementAndGet()
        binding.videoPreview.visibility = View.VISIBLE
        binding.tvPlayIcon.visibility = View.GONE
        val controller = MediaController(this)
        controller.setAnchorView(binding.videoPreview)
        binding.videoPreview.setMediaController(controller)
        binding.videoPreview.setOnPreparedListener { player ->
            player.isLooping = true
            binding.videoPreview.setVideoSize(player.videoWidth, player.videoHeight)
            binding.videoPreview.start()
        }
        binding.videoPreview.setOnErrorListener { _, _, _ ->
            binding.videoPreview.visibility = View.GONE
            binding.tvPlayIcon.visibility = View.VISIBLE
            true
        }
        binding.videoPreview.setVideoURI(Uri.parse(videoUrl), previewRequestHeaders())
        binding.videoPreview.requestFocus()
    }

    private fun stopPreviewVideo() {
        binding.videoPreview.stopPlayback()
        binding.videoPreview.clearVideoSize()
        binding.videoPreview.visibility = View.GONE
        binding.tvPlayIcon.visibility = View.GONE
    }

    private fun thumbnailUrl(resource: ResolvedResource?, preview: PyResolveResult?): String {
        val direct = resource?.downloadUrls?.firstOrNull().orEmpty()
        if (resource?.mediaType == "image" || resource?.mediaType == "cover") return direct
        return preview?.coverUrl.orEmpty().ifBlank {
            preview?.resources
                ?.firstOrNull { it.mediaType == "image" || it.mediaType == "cover" }
                ?.downloadUrls
                ?.firstOrNull()
                .orEmpty()
        }
    }

    private fun loadBitmapInto(
        imageUrl: String,
        imageView: ImageView,
        fallback: TextView,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = fetchBitmap(imageUrl, connectTimeoutMs, readTimeoutMs)
            launch(Dispatchers.Main) {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    fallback.visibility = View.GONE
                } else {
                    fallback.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun fetchBitmap(imageUrl: String, connectTimeoutMs: Int, readTimeoutMs: Int) =
        runCatching {
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            previewRequestHeaders().forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun previewRequestHeaders(): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to PREVIEW_USER_AGENT,
            "Referer" to PREVIEW_REFERER,
        )
        val cookie = StoreModule.getCookieString().orEmpty()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    private fun countResources(preview: PyResolveResult, mode: PreviewMode): Int =
        resourcesForMode(preview, mode).size

    private fun resourcesForMode(preview: PyResolveResult, mode: PreviewMode): List<ResolvedResource> {
        val selected = preview.resources.filter { it.selected }.ifEmpty { preview.resources }
        return when (mode) {
            PreviewMode.IMAGE -> selected.filter { it.mediaType == "image" || it.mediaType == "cover" }
            PreviewMode.VIDEO -> selected.filter { it.mediaType == "video" }
        }
    }

    private fun copyCurrentPreviewLink() {
        val source = currentPreview?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: currentPreviewInput?.takeIf { it.isNotBlank() }
            ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), source))
        toast(getString(R.string.main_toast_link_copied))
    }

    private fun saveCurrentPreview() {
        val input = currentPreviewInput.orEmpty()
        val preview = currentPreview
        if (input.isBlank() || preview == null) return
        hideSheets()
        startDownload(input, preview)
    }

    private fun showMineSheet() {
        refreshLoginState()
        refreshHistoryUi()
        binding.sheetMask.visibility = View.VISIBLE
        binding.mineSheet.visibility = View.VISIBLE
    }

    private fun hideSheets() {
        binding.sheetMask.visibility = View.GONE
        binding.mineSheet.visibility = View.GONE
    }

    private fun showClipboardDialog(input: String) {
        pendingClipboardInput = input
        binding.tvClipboardDialogMessage.text = getString(
            R.string.main_clipboard_dialog_message_format,
            input,
        )
        binding.dialogMask.visibility = View.VISIBLE
    }

    private fun hideClipboardDialog() {
        binding.dialogMask.visibility = View.GONE
        pendingClipboardInput = null
    }

    private fun styleActionButtons() {
        binding.btnClear.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnDownload.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnCopyLink.setStyle(DyActionButton.Style.GHOST)
        binding.btnSaveSheet.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnClipboardDismiss.setStyle(DyActionButton.Style.GHOST)
        binding.btnClipboardParse.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnLogin.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryOpen.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryShare.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnHistoryRetry.setStyle(DyActionButton.Style.PRIMARY)
        binding.btnHistoryClear.setStyle(DyActionButton.Style.SECONDARY)
        binding.btnCloseMineSheet.setStyle(DyActionButton.Style.GHOST)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showIndeterminateProgress(message: String) {
        progressDetailMessage = message
        binding.progressBubble.showIndeterminate(message)
    }

    private fun updateProgressBubble(
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
    ) {
        val downloadedText = formatBytes(downloadedBytes)
        val sizeText = if (totalBytes > EMPTY_BYTE_COUNT) {
            getString(R.string.main_progress_size_format, downloadedText, formatBytes(totalBytes))
        } else {
            getString(R.string.main_progress_size_unknown)
        }
        val speedText = if (speedBytesPerSecond > EMPTY_BYTE_COUNT) {
            getString(R.string.main_progress_speed_format, formatBytes(speedBytesPerSecond))
        } else {
            getString(R.string.main_progress_speed_unknown)
        }
        progressDetailMessage = "$sizeText · $speedText"
        if (totalBytes > EMPTY_BYTE_COUNT) {
            binding.progressBubble.showProgress(percent, progressDetailMessage)
        } else {
            binding.progressBubble.showIndeterminate(progressDetailMessage)
        }
    }

    private fun updateThumbnailSelection(index: Int) {
        for (childIndex in 0 until binding.thumbContainer.childCount) {
            binding.thumbContainer.getChildAt(childIndex).setBackgroundResource(
                if (childIndex == index) R.drawable.bg_thumb_selected else R.drawable.bg_thumb
            )
        }
        binding.thumbScroll.post {
            val selected = binding.thumbContainer.getChildAt(index) ?: return@post
            val targetY = (selected.top - (binding.thumbScroll.height - selected.height) / 2)
                .coerceAtLeast(0)
            binding.thumbScroll.smoothScrollTo(0, targetY)
        }
    }

    private fun setupProgressBubble() {
        binding.progressBubble.setOnClickListener {
            if (progressDetailMessage.isNotBlank()) toast(progressDetailMessage)
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        binding.progressBubble.setOnTouchListener { bubble, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = bubble.x
                    startY = bubble.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragged && (kotlin.math.abs(deltaX) > dp(4) || kotlin.math.abs(deltaY) > dp(4))) {
                        dragged = true
                    }
                    if (dragged) {
                        progressBubblePositioned = true
                        val minX = systemInsetLeft + dp(8).toFloat()
                        val maxX = (binding.root.width - systemInsetRight - bubble.width - dp(8))
                            .coerceAtLeast(minX.toInt()).toFloat()
                        val minY = systemInsetTop + dp(8).toFloat()
                        val bottomBoundary = binding.bottomNav.top.takeIf { it > 0 }
                            ?: (binding.root.height - systemInsetBottom)
                        val maxY = (bottomBoundary - bubble.height - dp(8))
                            .coerceAtLeast(minY.toInt()).toFloat()
                        bubble.x = (startX + deltaX).coerceIn(minX, maxX)
                        bubble.y = (startY + deltaY).coerceIn(minY, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) bubble.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    override fun applySystemBarInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemInsetTop = systemBars.top
            systemInsetBottom = systemBars.bottom
            systemInsetLeft = systemBars.left
            systemInsetRight = systemBars.right

            binding.tvAppTitle.layoutParams = binding.tvAppTitle.layoutParams.apply {
                height = dp(APP_HEADER_HEIGHT_DP) + systemBars.top
            }
            binding.tvAppTitle.setPadding(
                dp(16) + systemBars.left,
                systemBars.top,
                dp(16) + systemBars.right,
                0,
            )
            binding.contentPanel.setPadding(
                dp(8) + systemBars.left,
                dp(8),
                dp(8) + systemBars.right,
                dp(CONTENT_BOTTOM_NAV_SPACE_DP) + systemBars.bottom,
            )
            binding.bottomNav.layoutParams = binding.bottomNav.layoutParams.apply {
                height = dp(BOTTOM_NAV_HEIGHT_DP) + systemBars.bottom
            }
            binding.bottomNav.setPadding(
                dp(8) + systemBars.left,
                dp(4),
                dp(8) + systemBars.right,
                dp(4) + systemBars.bottom,
            )
            if (!progressBubblePositioned) {
                (binding.progressBubble.layoutParams as FrameLayout.LayoutParams).also { params ->
                    params.topMargin = systemBars.top + dp(8)
                    params.marginEnd = systemBars.right + dp(8)
                    binding.progressBubble.layoutParams = params
                }
                progressBubblePositioned = true
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    override fun onAbilityChanged(downloadType: DownloadType) {
        refreshPlatformUi()
    }

    private fun clearLinkAndCancelDownload() {
        binding.etUrl.text?.clear()
        clearPreview()
        toast(getString(R.string.main_status_link_cleared))
    }

    private fun saveDownloadHistory(
        sourceUrl: String,
        title: String,
        mediaType: String,
        status: String,
        savedUris: List<Uri>,
        errorMessage: String?,
        createdAt: Long,
    ) {
        DownloadHistoryStore.add(
            DownloadHistoryRecord(
                downloadId = createdAt.toString(),
                sourceUrl = sourceUrl,
                title = title,
                mediaType = mediaType,
                status = status,
                savedPath = savedUris.firstOrNull()?.toString().orEmpty(),
                savedUris = savedUris,
                errorMessage = errorMessage,
                createdAt = createdAt,
                finishedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun refreshHistoryUi() {
        val latest = DownloadHistoryStore.latest()
        binding.historySection.visibility = if (latest == null) View.GONE else View.VISIBLE
        if (latest == null) return

        binding.tvHistoryInfo.text = if (latest.isSuccess) {
            getString(
                R.string.main_history_success_format,
                latest.title,
                latest.sourceUrl,
                latest.savedUris.size,
            )
        } else {
            getString(
                R.string.main_history_failed_format,
                latest.title,
                latest.sourceUrl,
                latest.errorMessage ?: getString(R.string.main_error_unknown),
            )
        }
        val hasFiles = latest.savedUris.isNotEmpty()
        binding.btnHistoryOpen.isEnabled = hasFiles
        binding.btnHistoryShare.isEnabled = hasFiles
        binding.btnHistoryRetry.isEnabled = latest.sourceUrl.isNotBlank() && !isDownloading
    }

    private fun openLatestHistoryFile() {
        val uri = DownloadHistoryStore.latest()?.savedUris?.firstOrNull()
        if (uri == null) {
            toast(getString(R.string.main_toast_no_file_to_open))
            return
        }
        val mimeType = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_toast_no_app_for_file))
        }
    }

    private fun shareLatestHistoryFile() {
        val uris = DownloadHistoryStore.latest()?.savedUris.orEmpty()
        if (uris.isEmpty()) {
            toast(getString(R.string.main_toast_no_file_to_open))
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uris.first()) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.main_history_share)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.main_toast_no_app_for_file))
        }
    }

    private fun retryLatestHistory() {
        val sourceUrl = DownloadHistoryStore.latest()?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: return
        setInputText(sourceUrl)
        clearPreview()
        resolveAndRenderPreview(sourceUrl)
    }

    private fun readSharedText(intent: Intent?) {
        val sharedText = extractSharedText(intent)
        if (sharedText.isNotBlank()) {
            val result = PlatformResolver.resolve(sharedText)
            val inputText = result?.normalizedInput ?: sharedText
            setInputText(inputText)
            clearPreview()
            if (result == null) toast(getString(R.string.main_toast_only_douyin_supported))
        }
    }

    private fun scheduleClipboardCheck() {
        if (isDownloading) return
        lifecycleScope.launch {
            delay(CLIPBOARD_CHECK_DELAY_MS)
            checkClipboardForDouyinLink()
        }
    }

    private fun checkClipboardForDouyinLink() {
        if (isDownloading) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return
        val description = clipboard.primaryClipDescription ?: return
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            return
        }
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val result = PlatformResolver.resolve(text) ?: return
        if (result.url == lastClipboardPromptUrl) return
        val currentInput = binding.etUrl.text?.toString()?.trim().orEmpty()
        if (currentInput == result.normalizedInput) return
        lastClipboardPromptUrl = result.url
        showClipboardDialog(result.normalizedInput)
    }

    private fun refreshLoginState() {
        val needLogin = LoginModule.needLogin
        binding.loginSection.visibility = if (needLogin) View.VISIBLE else View.GONE
        if (!needLogin) return

        val cookieString = StoreModule.getCookieString().orEmpty()
        val loggedIn = StoreModule.loggedIn()
        binding.tvLoginState.text = if (loggedIn) {
            getString(R.string.main_status_logged_in)
        } else {
            getString(R.string.main_status_not_logged_in)
        }
        binding.btnLogin.text = if (loggedIn) {
            getString(R.string.main_button_relogin)
        } else {
            getString(R.string.main_button_login)
        }
        binding.accountDesc.text = getString(
            R.string.main_cookie_diagnostics_format,
            formatCookieFieldState(cookieString, "sessionid"),
            formatCookieFieldState(cookieString, "sso_uid_tt"),
            formatCookieFieldState(cookieString, "ttwid"),
            formatCookieFieldState(cookieString, "passport_csrf_token"),
            formatCookieFieldState(cookieString, "msToken"),
        )
    }

    private fun clearDouyinCookies() {
        com.zemin.downloader.common.util.LocalStorage.clearCookies(DownloadType.DOU_YIN.type)
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            lifecycleScope.launch {
                DownloadModule.refreshCookies("")
                refreshLoginState()
                toast(getString(R.string.main_toast_cookie_cleared))
            }
        }
    }

    private fun formatCookieFieldState(cookieString: String, field: String): String {
        return if (cookieString.contains("$field=")) {
            getString(R.string.main_cookie_field_present)
        } else {
            getString(R.string.main_cookie_field_missing)
        }
    }

    private fun refreshPlatformUi() {
        binding.tvAppTitle.text =
            getString(R.string.main_platform_selector_title_format, currentTitle)
        binding.tvInputTitle.text = getString(R.string.main_input_title_douyin)
        binding.etUrl.hint = getString(R.string.main_share_input_hint)
        refreshLoginState()
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.etUrl.isEnabled = enabled
        binding.btnLogin.isEnabled = enabled
        binding.btnDownload.isEnabled = enabled
        binding.btnClear.isEnabled = true
    }

    private fun showError(message: String?) {
        if (!message.isNullOrEmpty()) toast(message)
    }

    private fun showUnsupportedLink() {
        clearPreview()
        toast(getString(R.string.main_toast_only_douyin_supported))
    }

    private fun createDownloadProgressListener(): DownloadProgressListener =
        object : DownloadProgressListener {
            override fun onProgress(
                percent: Int,
                downloadedBytes: Long,
                totalBytes: Long,
                speedBytesPerSecond: Long,
            ) {
                if (!shouldDispatchProgressUpdate(downloadedBytes, totalBytes)) return

                lifecycleScope.launch(Dispatchers.Main) {
                    updateProgressBubble(
                        percent.coerceIn(PROGRESS_INIT, PROGRESS_COMPLETE),
                        downloadedBytes,
                        totalBytes,
                        speedBytesPerSecond,
                    )
                }
            }

            private fun shouldDispatchProgressUpdate(
                downloadedBytes: Long, totalBytes: Long
            ): Boolean {
                val now = SystemClock.elapsedRealtime()
                val isComplete = totalBytes > EMPTY_BYTE_COUNT && downloadedBytes >= totalBytes

                while (true) {
                    val lastUpdatedAt = lastProgressUiUpdatedAt.get()
                    val interval = now - lastUpdatedAt
                    if (!isComplete && lastUpdatedAt > 0L && interval < PROGRESS_UI_UPDATE_INTERVAL_MS) {
                        return false
                    }
                    if (lastProgressUiUpdatedAt.compareAndSet(lastUpdatedAt, now)) {
                        return true
                    }
                }
            }
        }

    private companion object {
        const val PROGRESS_INIT = 0
        const val PROGRESS_COMPLETE = 100
        const val PROGRESS_RECORD_INIT_TIME = 0L
        const val PROGRESS_HIDE_DELAY_MS = 1500L
        const val CLIPBOARD_CHECK_DELAY_MS = 500L
        const val PROGRESS_UI_UPDATE_INTERVAL_MS = 200L
        const val EMPTY_BYTE_COUNT = 0L
        const val APP_HEADER_HEIGHT_DP = 48
        const val BOTTOM_NAV_HEIGHT_DP = 58
        const val CONTENT_BOTTOM_NAV_SPACE_DP = 64
        const val PREVIEW_LOAD_INIT_TOKEN = 0L
        const val PREVIEW_IMAGE_CONNECT_TIMEOUT_MS = 5000
        const val PREVIEW_IMAGE_READ_TIMEOUT_MS = 8000
        const val PREVIEW_THUMB_CONNECT_TIMEOUT_MS = 3000
        const val PREVIEW_THUMB_READ_TIMEOUT_MS = 5000
        const val PREVIEW_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        const val PREVIEW_REFERER = "https://www.douyin.com/"
    }

    private enum class PreviewMode {
        IMAGE,
        VIDEO,
    }
}
