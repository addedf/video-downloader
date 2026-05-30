package com.zemin.downloader.update

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zemin.downloader.R
import com.zemin.downloader.common.util.toast
import kotlinx.coroutines.launch
import java.io.File

class UpdateManager(
    private val activity: AppCompatActivity,
) : DefaultLifecycleObserver {
    private val checker = UpdateChecker(activity.applicationContext)
    private var updateDialog: AlertDialog? = null
    private var checkedOnStart = false
    private var downloadId = INVALID_DOWNLOAD_ID
    private var apkFile: File? = null
    private var pendingInstallFile: File? = null
    private var downloadReceiver: BroadcastReceiver? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun checkOnStart() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (checkedOnStart) return@repeatOnLifecycle
                checkedOnStart = true
                runCatching { checker.check() }.onSuccess { info -> info?.let(::showUpdateDialog) }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        if (activity.isFinishing || updateDialog?.isShowing == true) return

        val dialogView = createUpdateDialogView(info)
        updateDialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(!info.forceUpdate)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { updateDialog = null }
                dialog.show()
                dialog.window?.applyRoundedDialogWindow()
            }
    }

    private fun createUpdateDialogView(info: UpdateInfo): View {
        return LayoutInflater.from(activity).inflate(R.layout.dialog_update, null, false).apply {
            findViewById<TextView>(R.id.tvUpdateVersion).text = activity.getString(
                R.string.update_dialog_version_format,
                updateVersionName(info),
            )
            findViewById<TextView>(R.id.tvUpdateContent).text = activity.getString(
                R.string.update_dialog_changelog_format,
                updateChangelog(info),
            )
            findViewById<View>(R.id.btnUpdateNow).setOnClickListener {
                updateDialog?.dismiss()
                openApkUrl(info.apkUrl)
            }
            findViewById<View>(R.id.btnUpdateLater).apply {
                visibility = if (info.forceUpdate) View.GONE else View.VISIBLE
                setOnClickListener { updateDialog?.dismiss() }
            }
            if (info.forceUpdate) {
                findViewById<View>(R.id.btnUpdateNow).clearStartMargin()
            }
        }
    }

    private fun View.clearStartMargin() {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginStart = 0
            layoutParams = params
        }
    }

    private fun Window.applyRoundedDialogWindow() {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(
            activity.resources.getDimensionPixelSize(R.dimen.update_dialog_width),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    override fun onResume(owner: LifecycleOwner) {
        pendingInstallFile?.takeIf { canInstallPackages() }?.let { file ->
            pendingInstallFile = null
            installApk(file)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterDownloadReceiver()
        updateDialog?.dismiss()
        updateDialog = null
    }

    private fun updateVersionName(info: UpdateInfo): String {
        return info.versionName.ifBlank {
            activity.getString(R.string.update_version_unknown)
        }
    }

    private fun updateChangelog(info: UpdateInfo): String {
        return info.changelog.ifBlank {
            activity.getString(R.string.update_changelog_empty)
        }
    }

    private fun openApkUrl(apkUrl: String) {
        if (apkUrl.isBlank()) {
            toast(activity.getString(R.string.update_toast_empty_apk_url))
            return
        }

        enqueueApkDownload(apkUrl)
    }

    private fun enqueueApkDownload(apkUrl: String) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val destination = createApkFile()
        destination.delete()
        apkFile = destination

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(activity.getString(R.string.update_download_title))
            .setDescription(activity.getString(R.string.update_download_desc))
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                UPDATE_APK_FILE_NAME,
            )

        registerDownloadReceiver()
        runCatching {
            downloadId = manager.enqueue(request)
        }.onSuccess {
            toast(activity.getString(R.string.update_toast_download_started))
        }.onFailure {
            unregisterDownloadReceiver()
            toast(activity.getString(R.string.update_toast_download_failed))
        }
    }

    private fun registerDownloadReceiver() {
        unregisterDownloadReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val completedId = intent?.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    INVALID_DOWNLOAD_ID,
                ) ?: INVALID_DOWNLOAD_ID
                if (completedId != downloadId) return

                unregisterDownloadReceiver()
                handleDownloadCompleted()
            }
        }
        downloadReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterDownloadReceiver() {
        val receiver = downloadReceiver ?: return
        runCatching { activity.unregisterReceiver(receiver) }
        downloadReceiver = null
    }

    private fun handleDownloadCompleted() {
        if (!isDownloadSuccessful()) {
            toast(activity.getString(R.string.update_toast_download_failed))
            return
        }

        val file = apkFile
        if (file == null || !file.exists()) {
            toast(activity.getString(R.string.update_toast_download_failed))
            return
        }

        installApk(file)
    }

    private fun isDownloadSuccessful(): Boolean {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return statusIndex >= 0 &&
                    cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        }
        return false
    }

    private fun installApk(file: File) {
        if (!canInstallPackages()) {
            pendingInstallFile = file
            openInstallPermissionSettings()
            return
        }

        val apkUri = FileProvider.getUriForFile(activity, fileProviderAuthority(), file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(activity.getString(R.string.update_toast_no_installer))
        } catch (_: SecurityException) {
            toast(activity.getString(R.string.update_toast_install_permission_required))
        }
    }

    private fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                activity.packageManager.canRequestPackageInstalls()
    }

    private fun openInstallPermissionSettings() {
        toast(activity.getString(R.string.update_toast_install_permission_required))
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("$PACKAGE_URI_SCHEME:${activity.packageName}"),
        )
        runCatching { activity.startActivity(intent) }
    }

    private fun createApkFile(): File {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.filesDir
        return File(dir, UPDATE_APK_FILE_NAME)
    }

    private fun fileProviderAuthority(): String {
        return "${activity.packageName}.fileprovider"
    }

    companion object {
        private const val INVALID_DOWNLOAD_ID = -1L
        private const val UPDATE_APK_FILE_NAME = "downloader-update.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val PACKAGE_URI_SCHEME = "package"
    }
}
