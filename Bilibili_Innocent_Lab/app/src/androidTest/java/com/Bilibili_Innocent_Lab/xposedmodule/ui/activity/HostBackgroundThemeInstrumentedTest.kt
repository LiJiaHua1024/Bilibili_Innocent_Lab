package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundThemeInstrumentedTest {
    /** 不改宿主主题设置；从用户原主题出发验证三个夜空预设及退出后的恢复。 */
    @Test fun celestialPresetsUseReadableDarkThemeAndOtherPresetsRestoreTheNativeTheme() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyHost") == "true")
        val test = InstrumentationRegistry.getInstrumentation()
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val context = test.targetContext
        val prefs = context.modulePreferences()
        val key = FeaturePreferences.HOST_BACKGROUND_PRESET
        val original = prefs.getString(key, null)
        val output = File(context.cacheDir, "host-background-theme-qa").apply { mkdirs() }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        }
        fun start(preset: String) {
            assertTrue(prefs.edit().putString(key, preset).commit())
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
            SystemClock.sleep(4000)
        }
        fun capture(name: String, checkContrast: Boolean): Int {
            var title: Rect? = null
            val deadline = SystemClock.uptimeMillis() + 10000
            while (title == null && SystemClock.uptimeMillis() < deadline) {
                val nodes = test.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("tv.danmaku.bili:id/title").orEmpty()
                title = nodes.map { node -> Rect().also { node.getBoundsInScreen(it) } }
                    .firstOrNull { it.left in 30..500 && it.width() > 100 && it.top in 500..1500 && it.height() > 20 }
                if (title == null) SystemClock.sleep(200)
            }
            val bounds = requireNotNull(title) { "No visible host video title for $name" }
            val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
            try {
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // 标题左侧的卡片内部留白，与标题字形分别取样，避免用壁纸亮度冒充文字可读性。
                val surface = bitmap.getPixel(bounds.left - 5, bounds.centerY()) or Color.BLACK
                if (checkContrast) {
                    assertTrue("$name must use a dark card surface", ColorUtils.calculateLuminance(surface) < .18)
                    var foreground = surface
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                        val pixel = bitmap.getPixel(x, y) or Color.BLACK
                        if (ColorUtils.calculateLuminance(pixel) > ColorUtils.calculateLuminance(foreground)) foreground = pixel
                    }
                    assertTrue("$name title must remain readable on its dark card", ColorUtils.calculateContrast(foreground, surface) >= 4.5)
                }
                return surface
            } finally { bitmap.recycle() }
        }
        try {
            start("off")
            val native = capture("native", false)
            for (preset in listOf("starry", "nebula", "meteor")) {
                start(preset)
                capture(preset, true)
            }
            start("aurora")
            assertEquals("A non-celestial preset must restore the original card theme", native, capture("restored-aurora", false))
            start("off")
            assertEquals("Disabling the background must restore the original theme", native, capture("restored-off", false))
        } finally {
            prefs.edit().apply { if (original == null) remove(key) else putString(key, original) }.commit()
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
        }
    }
}
