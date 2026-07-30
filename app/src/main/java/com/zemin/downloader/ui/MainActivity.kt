package com.zemin.downloader.ui

import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.webkit.CookieManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.common.DownloadProgressListener
import com.zemin.downloader.common.PyResolveResult
import com.zemin.downloader.common.ResolvedResource
import com.zemin.downloader.common.bean.DownloadRequest
import com.zemin.downloader.common.bean.DownloadSelection
import com.zemin.downloader.common.bean.DownloadSource
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
import com.zemin.downloader.ui.motion.MotionBottomSheetController
import com.zemin.downloader.ui.motion.UiMotion
import com.zemin.downloader.ui.util.PlatformResolver
import com.zemin.downloader.ui.util.extractSharedText
import com.zemin.downloader.ui.preview.PreviewUiPolicy
import com.zemin.downloader.ui.preview.PreviewImageController
import com.zemin.downloader.ui.preview.ResourceTab
import com.zemin.downloader.ui.view.DyActionButton
import com.zemin.downloader.update.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate) {
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshLoginState()
    }
    // Activity fields are initialized before Context.attachBaseContext. Defer construction because
    // AppUpdateManager reads applicationContext and SharedPreferences in its initializer.
    private val appUpdateManager by lazy(LazyThreadSafetyMode.NONE) { AppUpdateManager(this) }
    private var isDownloading = false
    private var currentPreview: PyResolveResult? = null
    private var currentPreviewInput: String? = null
    private var suppressInputChangeHandling = false
    private var lastClipboardPromptUrl: String? = null
    private var pendingClipboardInput: String? = null
    private var selectedResourceTab: ResourceTab = ResourceTab.IMAGE
    private var availableResourceTabs: List<ResourceTab> = emptyList()
    private var selectedPreviewIndex = 0
    private var progressDetailMessage = ""
    private var systemInsetTop = 0
    private var systemInsetBottom = 0
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var progressBubblePositioned = false
    private lateinit var mineSheetController: MotionBottomSheetController
    private lateinit var previewImages: PreviewImageController
    private val lastProgressUiUpdatedAt = AtomicLong(PROGRESS_RECORD_INIT_TIME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewImages = PreviewImageController(
            context = this,
            lifecycleOwner = this,
            scope = lifecycleScope,
            previewCard = binding.previewCard,
            ambientView = binding.ivPreviewAmbient,
            ambientScrim = binding.previewAmbientScrim,
            currentView = binding.ivPreviewCover,
            incomingView = binding.ivPreviewCoverIncoming,
        )
        setupMotion()
        setupProgressBubble()
        binding.videoPreview.bindFullscreen(this)
        readSharedText(intent)
        appUpdateManager.checkOnStart()
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
                input.isEmpty() -> {
                    showError(getString(R.string.main_toast_empty_input, currentTitle))
                }
                isDownloading -> {
                    showError(getString(R.string.main_toast_task_running))
                }
                else -> {
                    UiMotion.performHaptic(binding.btnDownload, UiMotion.Haptic.TICK)
                    resolveAndRenderPreview(input)
                }
            }
        }
        binding.btnClear.setOnClickListener {
            UiMotion.performHaptic(binding.btnClear, UiMotion.Haptic.TICK)
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
        binding.btnImageTab.setOnClickListener { selectResourceTab(tabAt(0), userInitiated = true) }
        binding.btnCoverTab.setOnClickListener { selectResourceTab(tabAt(1), userInitiated = true) }
        binding.btnAudioTab.setOnClickListener { selectResourceTab(tabAt(2), userInitiated = true) }
        binding.checkLiveVideo.setOnCheckedChangeListener { button, _ ->
            if (button.isPressed) UiMotion.performHaptic(button, UiMotion.Haptic.TICK)
            refreshSelectionUi()
        }
        binding.btnCopyLink.setOnClickListener {
            copyCurrentPreviewLink()
            UiMotion.performHaptic(binding.btnCopyLink, UiMotion.Haptic.CONFIRM)
        }
        binding.btnSaveSheet.setOnClickListener {
            UiMotion.performHaptic(binding.btnSaveSheet, UiMotion.Haptic.TICK)
            saveCurrentPreview()
        }
        binding.btnMine.setOnClickListener {
            UiMotion.performHaptic(binding.btnMine, UiMotion.Haptic.TICK)
            showMineSheet()
        }
        binding.btnCloseMineSheet.setOnClickListener { hideSheets() }
        binding.dialogMask.setOnClickListener { hideClipboardDialog() }
        binding.clipboardDialogPanel.setOnClickListener { }
        binding.btnClipboardDismiss.setOnClickListener { hideClipboardDialog() }
        binding.btnClipboardParse.setOnClickListener {
            val input = pendingClipboardInput.orEmpty()
            UiMotion.performHaptic(binding.btnClipboardParse, UiMotion.Haptic.TICK)
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

    override fun onPause() {
        binding.videoPreview.pausePlayback()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)
    }

    override fun onDestroy() {
        previewImages.dispose()
        binding.videoPreview.stopPlayback()
        mineSheetController.hideImmediately()
        hideClipboardDialog(immediate = true)
        super.onDestroy()
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

    private fun startDownload(
        shareText: String,
        preview: PyResolveResult? = null,
        request: DownloadRequest? = null,
    ) {
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
            val historyMediaType = request?.selection?.let { selection ->
                if (selection.resourceType == "image" && selection.includeLiveVideo) {
                    "image+live_video"
                } else {
                    selection.resourceType
                }
            } ?: preview?.mediaType ?: currentType
            try {
                StoreModule.cleanupDownloadCache()
                val result = DownloadModule.download(
                    inputText = shareText,
                    request = request,
                    progressListener = createDownloadProgressListener(),
                )

                val registeredUris = result.files.map(::File).mapNotNull { file ->
                    StoreModule.registerMediaFile(file)?.also {
                        StoreModule.deleteTemporaryDownloadFile(file)
                    }
                }

                if (result.ok || result.skipped > 0) {
                    progressDetailMessage = getString(R.string.main_status_download_done)
                    binding.progressBubble.showSuccess(progressDetailMessage)
                    UiMotion.performHaptic(binding.progressBubble, UiMotion.Haptic.CONFIRM)
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
                    showDownloadFailure(errorMessage)
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
                showDownloadFailure(errorMessage)
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
        val mediaTypeText = formatMediaType(preview.mediaType)
        val selectedResourceCount = preview.resources.size
        val title = preview.title.orEmpty().ifBlank { getString(R.string.main_preview_title_fallback) }
        val author = preview.author.orEmpty().ifBlank { currentTitle }
        val uiState = PreviewUiPolicy.stateFor(preview)
        if (uiState.tabs.isEmpty()) {
            clearPreview()
            showError(getString(R.string.main_error_no_resources))
            return
        }

        currentPreview = preview
        currentPreviewInput = inputText
        binding.tvPreviewTitle.text = title
        binding.tvPreviewMeta.text = getString(
            R.string.main_preview_meta_format,
            author,
            mediaTypeText,
            selectedResourceCount,
        )
        configureTabButtons(uiState.tabs, preview)
        refreshActionButton()
        selectedResourceTab = uiState.defaultTab
        binding.checkLiveVideo.isChecked = false
        selectResourceTab(selectedResourceTab, userInitiated = false)
        UiMotion.revealFromBelow(binding.previewSection)
        UiMotion.performHaptic(binding.previewSection, UiMotion.Haptic.CONFIRM)
    }

    private fun clearPreview() {
        currentPreview = null
        currentPreviewInput = null
        UiMotion.concealBelow(binding.previewSection)
        previewImages.clear()
        binding.videoPreview.stopPlayback()
        binding.videoPreview.visibility = View.GONE
        binding.thumbContainer.removeAllViews()
        selectedPreviewIndex = 0
        binding.checkLiveVideo.isChecked = false
        binding.checkLiveVideo.visibility = View.GONE
        binding.previewTabIndicator.visibility = View.INVISIBLE
        availableResourceTabs = emptyList()
        hideSheets()
        refreshActionButton()
    }

    private fun refreshActionButton() {
        binding.btnDownload.text = getString(R.string.main_button_download)
    }

    private fun selectResourceTab(tab: ResourceTab?, userInitiated: Boolean) {
        val selectedTab = tab ?: return
        val preview = currentPreview ?: return
        val resources = PreviewUiPolicy.resourcesFor(preview, selectedTab)
        if (resources.isEmpty()) return

        val changed = selectedResourceTab != selectedTab
        selectedResourceTab = selectedTab
        binding.btnImageTab.isSelected = tabAt(0) == selectedTab
        binding.btnCoverTab.isSelected = tabAt(1) == selectedTab
        binding.btnAudioTab.isSelected = tabAt(2) == selectedTab
        val selectedButton = listOf(
            binding.btnImageTab,
            binding.btnCoverTab,
            binding.btnAudioTab,
        )[availableResourceTabs.indexOf(selectedTab)]
        val updateIndicator = {
            if (currentPreview === preview && selectedResourceTab == selectedTab) {
                UiMotion.animateTabIndicator(
                    binding.previewTabIndicator,
                    selectedButton,
                    animated = userInitiated && changed,
                )
            }
        }
        if (userInitiated) updateIndicator() else binding.previewTabButtons.post { updateIndicator() }
        updatePreviewLabels(0, resources.size, selectedTab)
        selectedPreviewIndex = 0
        renderThumbnails(resources, selectedTab)
        updatePreviewResource(0, resources, selectedTab)
        refreshSelectionUi()
        if (userInitiated && changed) {
            UiMotion.performHaptic(selectedButton, UiMotion.Haptic.TICK)
        }
    }

    private fun configureTabButtons(tabs: List<ResourceTab>, preview: PyResolveResult) {
        availableResourceTabs = tabs
        val buttons = listOf(binding.btnImageTab, binding.btnCoverTab, binding.btnAudioTab)
        buttons.forEachIndexed { index, button ->
            val tab = tabs.getOrNull(index)
            button.visibility = if (tab == null) View.GONE else View.VISIBLE
            if (tab != null) {
                val count = PreviewUiPolicy.resourcesFor(preview, tab).size
                button.text = when (tab) {
                    ResourceTab.VIDEO -> getString(R.string.main_preview_tab_primary_video, count)
                    ResourceTab.IMAGE -> getString(R.string.main_preview_tab_image, count)
                    ResourceTab.COVER -> getString(R.string.main_preview_tab_cover, count)
                    ResourceTab.AUDIO -> getString(R.string.main_preview_tab_audio, count)
                }
            }
        }
    }

    private fun tabAt(index: Int): ResourceTab? = availableResourceTabs.getOrNull(index)

    private fun updatePreviewLabels(
        index: Int,
        total: Int,
        tab: ResourceTab,
    ) {
        binding.tvPreviewCounter.text = when (tab) {
            ResourceTab.IMAGE -> getString(
                R.string.main_preview_counter_image_format,
                index + 1,
                total,
            )
            ResourceTab.VIDEO -> getString(
                R.string.main_preview_counter_video_format,
                index + 1,
                total,
            )
            ResourceTab.COVER -> getString(R.string.main_preview_counter_cover)
            ResourceTab.AUDIO -> getString(R.string.main_preview_tab_audio, total)
        }
    }

    private fun refreshSelectionUi() {
        val preview = currentPreview ?: return
        val showLive = PreviewUiPolicy.shouldShowLiveOption(preview, selectedResourceTab)
        if (!showLive && binding.checkLiveVideo.isChecked) {
            binding.checkLiveVideo.isChecked = false
        }
        binding.checkLiveVideo.visibility = if (showLive) View.VISIBLE else View.GONE

        val resourceCount = PreviewUiPolicy.resourcesFor(preview, selectedResourceTab).size
        val includeLive = showLive && binding.checkLiveVideo.isChecked
        binding.btnSaveSheet.text = when (selectedResourceTab) {
            ResourceTab.VIDEO -> getString(R.string.main_save_video)
            ResourceTab.IMAGE -> if (includeLive) {
                getString(R.string.main_save_images_live)
            } else {
                getString(R.string.main_save_images, resourceCount)
            }
            ResourceTab.COVER -> getString(R.string.main_save_cover)
            ResourceTab.AUDIO -> getString(R.string.main_save_audio)
        }
    }

    private fun buildDownloadRequest(preview: PyResolveResult): DownloadRequest? {
        if (preview.schemaVersion != 2 || currentDownloadType != DownloadType.DOU_YIN) return null
        val sourceUrl = preview.sourceUrl?.takeIf { it.isNotBlank() }
            ?: currentPreviewInput.orEmpty()
        val sourceId = preview.sourceId.orEmpty()
        if (sourceUrl.isBlank() || sourceId.isBlank()) return null
        return DownloadRequest(
            source = DownloadSource(url = sourceUrl, id = sourceId),
            expectedWorkType = preview.mediaType.orEmpty(),
            selection = DownloadSelection(
                resourceType = selectedResourceTab.resourceType,
                includeLiveVideo = binding.checkLiveVideo.visibility == View.VISIBLE &&
                    binding.checkLiveVideo.isChecked,
            ),
        )
    }

    private fun renderThumbnails(resources: List<ResolvedResource>, tab: ResourceTab) {
        previewImages.clearThumbnails()
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
                    val changed = selectedPreviewIndex != index
                    updatePreviewResource(index, resources, tab)
                    if (changed) {
                        UiMotion.performHaptic(this, UiMotion.Haptic.TICK)
                    }
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
                    "audio" -> "音频"
                    else -> (index + 1).toString()
                }
            }
            thumb.addView(imageView)
            thumb.addView(fallback)
            binding.thumbContainer.addView(thumb)
            val thumbUrl = thumbnailUrl(resource, currentPreview)
            if (thumbUrl.isNotBlank()) {
                previewImages.loadThumbnail(
                    imageUrl = thumbUrl,
                    imageView = imageView,
                    fallback = fallback,
                    headers = previewRequestHeaders(),
                )
            }
        }
    }

    private fun updatePreviewResource(
        index: Int,
        resources: List<ResolvedResource>,
        tab: ResourceTab,
    ) {
        val preview = currentPreview ?: return
        val resource = resources.getOrNull(index) ?: return
        selectedPreviewIndex = index
        updateThumbnailSelection(index)
        updatePreviewLabels(index, resources.size, tab)
        if (tab == ResourceTab.VIDEO) {
            playPreviewVideo(resource)
        } else {
            stopPreviewVideo()
            loadPreviewImage(preview, resource, resources, index)
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
        "live_photo" -> getString(R.string.main_media_type_live_photo)
        else -> getString(R.string.main_media_type_unknown)
    }

    private fun loadPreviewImage(
        preview: PyResolveResult,
        resource: ResolvedResource? = null,
        resources: List<ResolvedResource> = emptyList(),
        index: Int = 0,
    ) {
        val imageUrl = thumbnailUrl(resource, preview)
        if (imageUrl.isBlank()) {
            previewImages.hideForVideo()
            return
        }
        val adjacentUrls = listOf(index - 1, index + 1)
            .mapNotNull(resources::getOrNull)
            .map { thumbnailUrl(it, preview) }
            .filter { it.isNotBlank() && it != imageUrl }
        previewImages.load(
            imageUrl = imageUrl,
            adjacentUrls = adjacentUrls,
            headers = previewRequestHeaders(),
        )
    }

    internal fun loadPreviewImageForTesting(imageUrl: String) {
        binding.previewSection.visibility = View.VISIBLE
        previewImages.load(
            imageUrl = imageUrl,
            adjacentUrls = emptyList(),
            headers = emptyMap(),
        )
    }

    private fun playPreviewVideo(resource: ResolvedResource) {
        val videoUrl = resource.downloadUrls.firstOrNull().orEmpty()
        previewImages.hideForVideo()
        if (videoUrl.isBlank()) {
            stopPreviewVideo()
            binding.videoPreview.showUnavailable()
            return
        }
        binding.videoPreview.visibility = View.VISIBLE
        binding.videoPreview.setVideo(
            uri = Uri.parse(videoUrl),
            headers = previewRequestHeaders(),
            autoPlay = true,
        )
    }

    private fun stopPreviewVideo() {
        binding.videoPreview.stopPlayback()
        binding.videoPreview.clearVideoSize()
        binding.videoPreview.visibility = View.GONE
    }

    private fun thumbnailUrl(resource: ResolvedResource?, preview: PyResolveResult?): String {
        val direct = resource?.previewUrls?.firstOrNull().orEmpty()
            .ifBlank { resource?.downloadUrls?.firstOrNull().orEmpty() }
        if (resource?.mediaType == "image" || resource?.mediaType == "cover") return direct
        return preview?.coverUrl.orEmpty().ifBlank {
            preview?.resources
                ?.firstOrNull { it.mediaType == "image" || it.mediaType == "cover" }
                ?.downloadUrls
                ?.firstOrNull()
                .orEmpty()
        }
    }

    private fun previewRequestHeaders(): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to PREVIEW_USER_AGENT,
            "Referer" to PREVIEW_REFERER,
        )
        val cookie = StoreModule.getCookieString().orEmpty()
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
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
        startDownload(input, preview, buildDownloadRequest(preview))
    }

    private fun setupMotion() {
        mineSheetController = MotionBottomSheetController(
            container = binding.sheetMask,
            scrim = binding.sheetScrim,
            sheet = binding.mineSheet,
        )
        UiMotion.bindPressFeedback(binding.btnMine)
        UiMotion.bindPressFeedback(binding.checkLiveVideo, pressedScale = 0.98f)
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.dialogMask.visibility == View.VISIBLE -> hideClipboardDialog()
                mineSheetController.isShowing -> hideSheets()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    private fun showMineSheet() {
        refreshLoginState()
        refreshHistoryUi()
        mineSheetController.show()
    }

    private fun hideSheets() {
        mineSheetController.hide()
    }

    private fun showClipboardDialog(input: String) {
        pendingClipboardInput = input
        binding.tvClipboardDialogMessage.text = getString(
            R.string.main_clipboard_dialog_message_format,
            input,
        )
        UiMotion.showDialog(binding.dialogMask, binding.clipboardDialogPanel)
    }

    private fun hideClipboardDialog(immediate: Boolean = false) {
        if (immediate) {
            binding.dialogMask.animate().cancel()
            binding.clipboardDialogPanel.animate().cancel()
            binding.dialogMask.visibility = View.GONE
            pendingClipboardInput = null
            return
        }
        UiMotion.hideDialog(binding.dialogMask, binding.clipboardDialogPanel) {
            pendingClipboardInput = null
        }
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
        UiMotion.bindEdgeSnap(
            view = binding.progressBubble,
            boundsProvider = {
                val bubble = binding.progressBubble
                val minX = systemInsetLeft + dp(8).toFloat()
                val maxX = (binding.root.width - systemInsetRight - bubble.width - dp(8))
                    .coerceAtLeast(minX.toInt()).toFloat()
                val minY = systemInsetTop + dp(8).toFloat()
                val bottomBoundary = binding.bottomNav.top.takeIf { it > 0 }
                    ?: (binding.root.height - systemInsetBottom)
                val maxY = (bottomBoundary - bubble.height - dp(8))
                    .coerceAtLeast(minY.toInt()).toFloat()
                RectF(minX, minY, maxX, maxY)
            },
            onDragStarted = { progressBubblePositioned = true },
        )
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

    private fun showDownloadFailure(message: String?) {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.main_error_unknown)
        progressDetailMessage = detail
        binding.progressBubble.showError(detail)
        showError(detail)
    }

    private fun showError(message: String?) {
        if (message.isNullOrEmpty()) return
        UiMotion.reject(binding.downloadSection)
        UiMotion.performHaptic(binding.downloadSection, UiMotion.Haptic.REJECT)
        toast(message)
    }

    private fun showUnsupportedLink() {
        clearPreview()
        showError(getString(R.string.main_toast_only_douyin_supported))
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
        const val PREVIEW_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        const val PREVIEW_REFERER = "https://www.douyin.com/"
    }

}
