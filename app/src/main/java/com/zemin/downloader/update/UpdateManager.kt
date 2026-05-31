package com.zemin.downloader.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zemin.downloader.R
import com.zemin.downloader.common.util.toast
import kotlinx.coroutines.launch

class UpdateManager(
    private val activity: AppCompatActivity,
) : DefaultLifecycleObserver {
    private val checker = UpdateChecker(activity.applicationContext)
    private var updateDialog: AlertDialog? = null
    private var checkedOnStart = false

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
                openApkDownloadUrl(info.apkUrl)
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

    override fun onDestroy(owner: LifecycleOwner) {
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

    private fun openApkDownloadUrl(apkUrl: String) {
        if (apkUrl.isBlank()) {
            toast(activity.getString(R.string.update_toast_empty_apk_url))
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(activity.getString(R.string.update_toast_no_browser))
        }
    }
}
