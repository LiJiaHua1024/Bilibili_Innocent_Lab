package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameRateValidationActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSubtitleDialogTest {
    @Test fun draftCancellationSaveAndOfficialResetWorkInTheRealWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Use an isolated verification application", context.packageName.endsWith(".subtitleverify"))
        val store = SubtitleStyleStore(context)
        store.save(null)
        ActivityScenario.launch<FrameRateValidationActivity>(android.content.Intent(context, FrameRateValidationActivity::class.java).putExtra("targetFps", 60)).use { scenario ->
            scenario.onActivity { it.setContentView(android.widget.FrameLayout(it)) }
            lateinit var dialog: AlertDialog
            fun openAndAdjust() {
                scenario.onActivity { activity ->
                    dialog = SubtitleStyleDialog.show(activity, store) { false }
                    val slider = descendants(dialog.window!!.decorView).filterIsInstance<SeekBar>().first()
                    assertTrue(slider.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 130f) }))
                    assertEquals(180, descendants(dialog.window!!.decorView).filterIsInstance<SubtitleStylePreview>().single().style.sizePercent)
                }
            }
            openAndAdjust()
            instrumentation.waitForIdleSync()
            if (InstrumentationRegistry.getArguments().getString("captureSubtitleUi") == "true") {
                val copied = java.util.concurrent.CountDownLatch(1)
                var result = -1
                val view = dialog.window!!.decorView
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(dialog.window!!, image, { result = it; copied.countDown() },
                    android.os.Handler(android.os.Looper.getMainLooper()))
                assertTrue(copied.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(android.view.PixelCopy.SUCCESS, result)
                java.io.File(context.cacheDir, "subtitle-settings-preview.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            assertNull("A draft must not persist", store.read())
            scenario.onActivity { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            assertNull("Cancel must keep the official defaults", store.read())
            openAndAdjust()
            scenario.onActivity { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await { store.read()?.sizePercent == 180 && !dialog.isShowing }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                dialog = SubtitleStyleDialog.show(activity, store) { false }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            await { store.read() == null }
            instrumentation.waitForIdleSync()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (!predicate() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(25)
        assertTrue("Subtitle settings did not finish saving", predicate())
    }
}
