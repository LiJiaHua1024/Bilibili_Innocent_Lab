package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.view.View
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HostVideoCardRadiusInstrumentedTest {
    // 验证生产点击处理与持久化；无需系统 INJECT_EVENTS 权限。
    private fun click() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isEnabled()
        override fun getDescription() = "Click radius dialog control"
        override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
            generateSequence(view) { it.parent as? View }.first { it.isClickable }.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    @Test fun radiusDialogSavesEndpointsRestoresDefaultAndCancelsWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.modulePreferences()
        val key = FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP
        val original = prefs.all[key]
        try {
            // 部分系统拦截 instrumentation 从后台启动 Activity；先由 shell 带到前台。
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/.ui.activity.MainActivity"
            ).let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var saved = 0
                fun open(radius: Int? = null) {
                    scenario.onActivity { activity ->
                        activity.showHostVideoCardRadiusDialog(activity.window.decorView) { saved++ }
                    }
                    if (radius != null) onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(SeekBar::class.java))
                        .inRoot(isDialog()).perform(object : androidx.test.espresso.ViewAction {
                            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(SeekBar::class.java)
                            override fun getDescription() = "Set video card radius"
                            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                                (view as SeekBar).progress = radius
                                uiController.loopMainThreadUntilIdle()
                            }
                        })
                }
                for (radius in listOf(0, 40)) {
                    open(radius)
                    onView(withText(context.getString(R.string.dialog_confirm))).inRoot(isDialog()).perform(click())
                    assertEquals(radius, prefs.getInt(key, -2))
                }
                open(8)
                onView(withText(context.getString(R.string.dialog_cancel))).inRoot(isDialog()).perform(click())
                assertEquals(40, prefs.getInt(key, -2))
                open()
                android.os.SystemClock.sleep(450L)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "video-card-radius-dialog.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                onView(withText(context.getString(R.string.host_video_card_radius_default))).inRoot(isDialog()).perform(click())
                assertEquals(-1, prefs.getInt(key, -2))
                assertEquals(3, saved)
            }
        } finally {
            prefs.edit().apply { if (original is Int) putInt(key, original) else remove(key) }.commit()
        }
    }
}
