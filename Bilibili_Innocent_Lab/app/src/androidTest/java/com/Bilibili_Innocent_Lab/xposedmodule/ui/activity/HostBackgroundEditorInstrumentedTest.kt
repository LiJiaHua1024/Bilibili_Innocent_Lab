package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class HostBackgroundEditorInstrumentedTest {
    /** 本机宿主切页验证，只有显式 verifyHost=true 才执行设备坐标操作。 */
    @Test fun backgroundRemainsStationaryAcrossHostScrollAndTabChanges() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("verifyHost") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.modulePreferences()
        val keys = listOf(FeaturePreferences.HOST_BACKGROUND_PRESET, FeaturePreferences.HOST_VIDEO_CARDS)
        val original = prefs.all.filterKeys { it in keys }
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
        }
        fun rootInput(arguments: String) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // executeShellCommand 不解析 shell 引号；通过 stdin 给 root shell 传完整命令。
                val descriptors = instrumentation.uiAutomation.executeShellCommandRw("su -c sh")
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use {
                    it.write("id\ninput $arguments\nexit\n".toByteArray())
                }
                val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
                assertTrue("Root input shell did not start: $output", output.contains("uid=0(root)"))
            } else error("Optional host navigation QA requires API 31+")
        }
        fun capture(name: String): Pair<Int, Int> {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            // 热门为铺满宽度的单列卡片，取上方入口区；双列推荐取侧边留白。
            val pixel = bitmap.getPixel(4, if (name == "hot-tab") 450 else bitmap.height / 3)
            var contentHash = 0
            for (y in 500..1600 step 100) for (x in 120..960 step 120) contentHash = 31 * contentHash + bitmap.getPixel(x, y)
            File(context.cacheDir, "host-background-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            return pixel to contentHash
        }
        try {
            prefs.edit().putString(FeaturePreferences.HOST_BACKGROUND_PRESET, "aurora")
                .putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, true).commit()
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
            android.os.SystemClock.sleep(4000)
            // 坐标已按本机 1080×2340 的当前推荐页截图确认；先切页，避免滚动折叠顶栏后沿用坐标。
            rootInput("tap 440 320")
            android.os.SystemClock.sleep(4000)
            val hot = capture("hot-tab")
            assertTrue("Hot tab must receive the selected background", android.graphics.Color.green(hot.first) > android.graphics.Color.red(hot.first) + 5)
            rootInput("tap 294 320")
            android.os.SystemClock.sleep(1200)
            val returned = capture("recommend-return")
            assertTrue(android.graphics.Color.green(returned.first) > android.graphics.Color.red(returned.first) + 5)
            // 首次滑动收起搜索栏，会改变列表边界；边界稳定后再验证背景不随卡片滚动。
            rootInput("swipe 540 1650 540 750 350")
            android.os.SystemClock.sleep(1200)
            val before = capture("scroll-before")
            rootInput("swipe 540 1650 540 750 350")
            android.os.SystemClock.sleep(1200)
            val after = capture("scroll-after")
            assertNotEquals("The cards must actually scroll", before.second, after.second)
            assertEquals("Background must stay fixed while cards scroll", before.first, after.first)
        } finally {
            prefs.edit().apply { keys.forEach { key -> when (val value = original[key]) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                else -> remove(key)
            } } }.commit()
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
        }
    }

    private fun click() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isEnabled()
        override fun getDescription() = "Click background editor control"
        override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
            generateSequence(view) { it.parent as? View }.first { it.isClickable }.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    @Test fun presetsCancelAndImageImportUseProductionEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.modulePreferences()
        val keys = listOf(FeaturePreferences.HOST_BACKGROUND_PRESET, FeaturePreferences.HOST_BACKGROUND_ASSET,
            FeaturePreferences.HOST_BACKGROUND_BLUR, FeaturePreferences.HOST_BACKGROUND_SATURATION, FeaturePreferences.HOST_BACKGROUND_VEIL,
            FeaturePreferences.HOST_VIDEO_CARDS)
        val original = prefs.all.filterKeys { it in keys }
        val oldAsset = original[FeaturePreferences.HOST_BACKGROUND_ASSET] as? String
        val assetBackup = oldAsset?.takeIf(HostBackgroundConfig::validAsset)?.let {
            HostBackgroundImages.file(context, it).takeIf(File::isFile)?.copyTo(File(context.cacheDir, "host-background-old.png"), overwrite = true)
        }
        val verifyHost = InstrumentationRegistry.getArguments().getString("verifyHost") == "true"
        fun button(res: Int) = onView(allOf(withText(context.getString(res)), isClickable())).inRoot(isDialog())
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).let { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
        }
        fun captureHost(name: String) {
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
            android.os.SystemClock.sleep(3500)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "host-background-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (name.startsWith("custom")) {
                    // 本机双列间的中央留白：测试图中段偏紫，回退极光偏绿，不能把回退误当图片成功。
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue("Custom image was not rendered in the host: ${Integer.toHexString(pixel)}",
                        android.graphics.Color.blue(pixel) > android.graphics.Color.green(pixel) + 10)
                }
                bitmap.recycle()
            }
        }
        val fixture = File(context.cacheDir, "host-background-fixture.png")
        val image = Bitmap.createBitmap(720, 1440, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 720f, 1440f,
            intArrayOf(0xff80c8c0.toInt(), 0xff967cbf.toInt(), 0xffefba91.toInt()), null, Shader.TileMode.CLAMP) })
        fixture.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var saved = 0
                fun open() = scenario.onActivity { activity ->
                    HostBackgroundEditor(activity, activity.window.decorView) { saved++ }.also {
                        activity.hostBackgroundEditor = it; it.show()
                    }
                }
                open()
                button(R.string.host_background_sakura).perform(click())
                button(R.string.dialog_cancel).perform(click())
                assertEquals(original[FeaturePreferences.HOST_BACKGROUND_PRESET], prefs.all[FeaturePreferences.HOST_BACKGROUND_PRESET])
                for ((label, value) in listOf(R.string.host_background_aurora to "aurora", R.string.host_background_sakura to "sakura",
                    R.string.host_background_ocean to "ocean", R.string.host_background_sunset to "sunset", R.string.host_background_mist to "mist")) {
                    open()
                    button(label).perform(click())
                    button(R.string.dialog_confirm).perform(click())
                    assertEquals(value, prefs.getString(FeaturePreferences.HOST_BACKGROUND_PRESET, ""))
                }
                open()
                scenario.onActivity { it.hostBackgroundEditor!!.importImage(Uri.fromFile(fixture)) }
                // 同一 worker 的屏障确保导入、预览完成，避免固定长等待。
                context.let {
                    scenario.onActivity { activity -> activity.liquidBackgroundWorker.submit {}.get() }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> activity.liquidBackgroundWorker.submit {}.get() }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "host-background-editor.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                button(R.string.dialog_confirm).perform(click())
                assertEquals("custom", prefs.getString(FeaturePreferences.HOST_BACKGROUND_PRESET, ""))
                assertTrue(prefs.getString(FeaturePreferences.HOST_BACKGROUND_ASSET, "").orEmpty().isNotEmpty())
                assertEquals(6, saved)
                if (verifyHost) {
                    captureHost("custom")
                    prefs.edit().putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, false).commit()
                    captureHost("custom-without-cards")
                    prefs.edit().putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, true)
                        .putString(FeaturePreferences.HOST_BACKGROUND_PRESET, "aurora").commit()
                    captureHost("aurora")
                    prefs.edit().putString(FeaturePreferences.HOST_BACKGROUND_PRESET, "sunset").commit()
                    captureHost("sunset")
                }
            }
        } finally {
            val testAsset = prefs.getString(FeaturePreferences.HOST_BACKGROUND_ASSET, "").orEmpty()
            prefs.edit().apply {
                keys.forEach { key -> when (val value = original[key]) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> remove(key)
                } }
            }.commit()
            if (testAsset != oldAsset && HostBackgroundConfig.validAsset(testAsset)) {
                context.revokeUriPermission(HostBackgroundImages.uri(testAsset), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                HostBackgroundImages.file(context, testAsset).delete()
            }
            // 保存测试图时生产清理器可能删除旧图，恢复原文件与 URI 授权。
            if (assetBackup != null) {
                assetBackup.copyTo(HostBackgroundImages.file(context, oldAsset), overwrite = true)
                assetBackup.delete()
                HostBackgroundImages.grant(context, oldAsset)
            }
            if (verifyHost) {
                shell("am force-stop tv.danmaku.bili")
                shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
            }
            fixture.delete()
        }
    }
}
