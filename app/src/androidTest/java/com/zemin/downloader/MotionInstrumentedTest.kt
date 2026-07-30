package com.zemin.downloader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zemin.downloader.ui.MainActivity
import com.zemin.downloader.ui.view.DownloadProgressBubbleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionInstrumentedTest {
    @Test
    fun previewUsesAmbientCropBehindCompleteForegroundImage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val ambient = activity.findViewById<ImageView>(R.id.ivPreviewAmbient)
        val foreground = activity.findViewById<ImageView>(R.id.ivPreviewCover)
        val incoming = activity.findViewById<ImageView>(R.id.ivPreviewCoverIncoming)

        assertEquals(ImageView.ScaleType.CENTER_CROP, ambient.scaleType)
        assertEquals(ImageView.ScaleType.FIT_CENTER, foreground.scaleType)
        assertEquals(ImageView.ScaleType.FIT_CENTER, incoming.scaleType)
        assertEquals(1.08f, ambient.scaleX, 0.001f)
        assertEquals(1.08f, ambient.scaleY, 0.001f)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun bottomSheetOpensAndBackHidesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val sheetMask = activity.findViewById<View>(R.id.sheetMask)

        instrumentation.runOnMainSync {
            activity.findViewById<View>(R.id.btnMine).performClick()
        }
        assertTrue(waitUntil { sheetMask.visibility == View.VISIBLE })

        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertTrue(waitUntil { sheetMask.visibility == View.GONE })

        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun progressBubbleUsesHardwareShadowAndPreservesDraggedPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        clearClipboard(instrumentation.targetContext)
        val activity = startMainActivity(instrumentation)
        val bubble = activity.findViewById<DownloadProgressBubbleView>(R.id.progressBubble)

        instrumentation.runOnMainSync {
            bubble.showSuccess("完成")
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            bubble.x = 120f
            bubble.y = 240f
        }
        assertEquals(View.VISIBLE, bubble.visibility)
        assertNotEquals(View.LAYER_TYPE_SOFTWARE, bubble.layerType)
        assertEquals(120f, bubble.x, 0.5f)
        assertEquals(240f, bubble.y, 0.5f)

        instrumentation.runOnMainSync { bubble.hide() }
        assertTrue(waitUntil { bubble.visibility == View.GONE })
        assertEquals(120f, bubble.x, 0.5f)
        assertEquals(240f, bubble.y, 0.5f)

        instrumentation.runOnMainSync { bubble.showProgress(35, "35%") }
        assertEquals(View.VISIBLE, bubble.visibility)
        assertEquals(120f, bubble.x, 0.5f)
        assertEquals(240f, bubble.y, 0.5f)

        instrumentation.runOnMainSync { activity.finish() }
    }

    private fun startMainActivity(
        instrumentation: android.app.Instrumentation,
    ): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun clearClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.clearPrimaryClip()
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(32L)
        }
        return condition()
    }
}
