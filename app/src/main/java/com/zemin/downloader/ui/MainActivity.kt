package com.zemin.downloader.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.common.DownloadProgressListener
import com.zemin.downloader.common.base.BaseActivity
import com.zemin.downloader.common.bean.formatDownloadSummary
import com.zemin.downloader.common.core.BridgeAbilityManager
import com.zemin.downloader.common.core.DownloadModule
import com.zemin.downloader.common.core.LoginModule
import com.zemin.downloader.common.core.StoreModule
import com.zemin.downloader.common.core.currentDownloadType
import com.zemin.downloader.common.core.currentTitle
import com.zemin.downloader.common.util.formatBytes
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.ActivityMainBinding
import com.zemin.downloader.impl.BridgeAbilityConfig
import com.zemin.downloader.impl.DownloadType
import com.zemin.downloader.ui.util.extractSharedText
import com.zemin.downloader.update.UpdateManager
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
    private lateinit var titleSelectorView: TextView
    private var isDownloading = false
    private var switchingDialog: AlertDialog? = null
    private val lastProgressUiUpdatedAt = AtomicLong(PROGRESS_RECORD_INIT_TIME)
    private val updateManager by lazy { UpdateManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPlatformSelector()
        readSharedText(intent)
        updateManager.checkOnStart()

        binding.btnLogin.setOnClickListener {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
        }
        binding.btnDownload.setOnClickListener {
            val input = binding.etUrl.text.toString().trim()
            when {
                input.isEmpty() -> toast(getString(R.string.main_toast_empty_input, currentTitle))
                isDownloading -> toast(getString(R.string.main_toast_task_running))
                else -> startDownload(input)
            }
        }
        binding.btnClear.setOnClickListener {
            clearLinkAndCancelDownload()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSharedText(intent)
    }

    override fun onDestroy() {
        dismissSwitchingDialog()
        super.onDestroy()
    }

    private fun startDownload(shareText: String) {
        if (LoginModule.needLogin) {
            val loggedIn = LoginModule.isLoggedIn(StoreModule.getCookieString().orEmpty())

            if (!loggedIn) {
                showError(getString(R.string.main_toast_need_login, currentTitle))
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
                return
            }
        }

        isDownloading = true
        setUiEnabled(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.progressBar.progress = PROGRESS_INIT
        binding.progressDetail.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.main_status_downloading)
        binding.tvInfo.visibility = View.VISIBLE
        binding.tvInfo.text = getString(
            R.string.main_output_dir_format, StoreModule.getDownloadDir()
        )
        updateProgressDetail(
            downloadedBytes = EMPTY_BYTE_COUNT,
            totalBytes = EMPTY_BYTE_COUNT,
            speedBytesPerSecond = EMPTY_BYTE_COUNT,
        )
        lastProgressUiUpdatedAt.set(PROGRESS_RECORD_INIT_TIME)

        lifecycleScope.launch {
            val taskStartedAt = System.currentTimeMillis()
            try {
                StoreModule.cleanupDownloadCache()
                val result = DownloadModule.download(
                    inputText = shareText,
                    progressListener = createDownloadProgressListener(),
                )

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = PROGRESS_COMPLETE

                val mediaRegisterStartedAt = System.currentTimeMillis()
                val registeredUris = result.files.map(::File).mapNotNull { file ->
                    StoreModule.registerMediaFile(file)?.also {
                        StoreModule.deleteTemporaryDownloadFile(file)
                    }
                }
                val mediaRegisterMs = (System.currentTimeMillis() - mediaRegisterStartedAt).toInt()
                val taskTotalMs = (System.currentTimeMillis() - taskStartedAt).toInt()

                if (result.ok || result.skipped > 0) {
                    StoreModule.cleanupDownloadSidecars()
                    binding.tvStatus.text = getString(R.string.main_status_download_done)
                    binding.tvInfo.visibility = View.VISIBLE
                    binding.tvInfo.text = result.formatDownloadSummary(
                        mediaCount = registeredUris.size,
                        mediaRegisterMs = mediaRegisterMs,
                        taskTotalMs = taskTotalMs,
                    )
                    toast(getString(R.string.main_toast_download_done))
                } else {
                    showError(result.error ?: result.message)
                }
            } catch (e: Exception) {
                showError(
                    getString(
                        R.string.main_error_exception,
                        e.message ?: getString(R.string.main_error_unknown)
                    )
                )
            } finally {
                isDownloading = false
                setUiEnabled(true)
                lifecycleScope.launch {
                    delay(PROGRESS_HIDE_DELAY_MS)
                    binding.progressBar.visibility = View.GONE
                    binding.progressDetail.visibility = View.GONE
                    binding.progressBar.isIndeterminate = false
                }
            }
        }
    }

    private fun updateProgressDetail(
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
    ) {
        val downloadedText = formatBytes(downloadedBytes)
        binding.tvProgressSize.text = if (totalBytes > EMPTY_BYTE_COUNT) {
            getString(R.string.main_progress_size_format, downloadedText, formatBytes(totalBytes))
        } else {
            getString(R.string.main_progress_size_unknown)
        }
        binding.tvProgressSpeed.text = if (speedBytesPerSecond > EMPTY_BYTE_COUNT) {
            getString(R.string.main_progress_speed_format, formatBytes(speedBytesPerSecond))
        } else {
            getString(R.string.main_progress_speed_unknown)
        }
    }

    private fun setupPlatformSelector() {
        val selector = LayoutInflater.from(this).inflate(
            R.layout.view_platform_selector, binding.root, false
        ) as TextView
        selector.apply {
            setOnClickListener { view -> showPlatformMenu(view) }
        }
        titleSelectorView = selector

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(false)
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setCustomView(
                selector, ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
            )
        }
    }

    private fun showPlatformMenu(anchor: View) {
        if (isDownloading) {
            toast(getString(R.string.main_toast_switch_after_download))
            return
        }

        PopupMenu(this, anchor).apply {
            menu.setGroupCheckable(0, true, true)
            val allAbility = BridgeAbilityConfig.getAllAbility()
            allAbility.forEachIndexed { index, downloadType ->
                menu.add(
                    0,
                    index,
                    index,
                    getString(R.string.main_platform_title_format, downloadType.title)
                ).isChecked = downloadType == currentDownloadType
            }
            setOnMenuItemClickListener { item ->
                val selectedType = allAbility[item.itemId]
                updateDownloadType(selectedType)
                true
            }
            show()
        }
    }

    private fun updateDownloadType(downloadType: DownloadType) {
        if (downloadType == currentDownloadType) return

        setUiEnabled(false)
        showSwitchingDialog()
        lifecycleScope.launch {
            try {
                BridgeAbilityManager.update(downloadType)
                binding.tvStatus.text = getString(R.string.main_status_waiting_input)
                binding.tvInfo.visibility = View.GONE
                toast(getString(R.string.main_toast_platform_changed, currentTitle))
            } finally {
                dismissSwitchingDialog()
                setUiEnabled(true)
            }
        }
    }

    override fun onAbilityChanged(downloadType: DownloadType) {
        refreshPlatformUi()
    }

    private fun showSwitchingDialog() {
        if (switchingDialog?.isShowing == true) return

        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.dialog_switching_platform, binding.root, false
        )
        switchingDialog =
            AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
                .also { it.show() }
    }

    private fun dismissSwitchingDialog() {
        switchingDialog?.dismiss()
        switchingDialog = null
    }

    private fun clearLinkAndCancelDownload() {
        binding.etUrl.text?.clear()
        binding.tvStatus.text = getString(R.string.main_status_link_cleared)
        binding.tvInfo.visibility = View.GONE
    }

    private fun readSharedText(intent: Intent?) {
        val sharedText = extractSharedText(intent)
        if (sharedText.isNotBlank()) {
            binding.etUrl.setText(sharedText)
            binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
            binding.tvStatus.text = getString(R.string.main_status_share_received)
            binding.tvInfo.visibility = View.GONE
        }
    }

    private fun refreshLoginState() {
        val needLogin = LoginModule.needLogin
        binding.loginSection.visibility = if (needLogin) View.VISIBLE else View.GONE
        if (!needLogin) return

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
        binding.accountDesc.text = getString(R.string.main_account_format, currentTitle)
    }

    private fun refreshPlatformUi() {
        titleSelectorView.text =
            getString(R.string.main_platform_selector_title_format, currentTitle)
        binding.tvInputTitle.text = getString(R.string.main_input_title_format, currentTitle)
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
        binding.tvStatus.text = getString(R.string.main_status_error)
        binding.tvInfo.visibility = View.VISIBLE
        if (!message.isNullOrEmpty()) {
            binding.tvInfo.text = message
            toast(message)
        }
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
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressDetail.visibility = View.VISIBLE
                    binding.progressBar.isIndeterminate = totalBytes <= EMPTY_BYTE_COUNT
                    if (totalBytes > EMPTY_BYTE_COUNT) {
                        binding.progressBar.progress = percent.coerceIn(0, PROGRESS_COMPLETE)
                    }
                    updateProgressDetail(downloadedBytes, totalBytes, speedBytesPerSecond)
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
        const val PROGRESS_UI_UPDATE_INTERVAL_MS = 200L
        const val EMPTY_BYTE_COUNT = 0L
    }
}
