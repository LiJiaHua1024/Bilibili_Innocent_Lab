package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅显式 profileHost=true 时执行本机滚动采样，记录数据而不把设备性能设为 CI 门槛。 */
@RunWith(AndroidJUnit4::class)
class HostBackgroundPerformanceInstrumentedTest {
    @Test fun compareScrollFrameStats() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileHost") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.modulePreferences()
        val keys = listOf(FeaturePreferences.HOST_BACKGROUND_PRESET, FeaturePreferences.HOST_BACKGROUND_ASSET,
            FeaturePreferences.HOST_BACKGROUND_BLUR, FeaturePreferences.HOST_BACKGROUND_SATURATION,
            FeaturePreferences.HOST_BACKGROUND_VEIL, FeaturePreferences.HOST_VIDEO_CARDS)
        val original = prefs.all.filterKeys { it in keys }
        val output = File(context.cacheDir, "host-background-performance").apply { mkdirs() }
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).let {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        fun root(commands: String) {
            check(android.os.Build.VERSION.SDK_INT >= 31)
            val descriptors = instrumentation.uiAutomation.executeShellCommandRw("su -c sh")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { it.write("id\n$commands\nexit\n".toByteArray()) }
            val result = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).bufferedReader().use { it.readText() }
            assertTrue("Root input failed: $result", result.contains("uid=0(root)") && !result.contains("Error"))
        }
        fun snapshot(name: String) {
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val fixture = File(context.cacheDir, "host-background-perf-fixture.png")
        val image = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1080f, 2160f,
            intArrayOf(0xff80c8c0.toInt(), 0xff967cbf.toInt(), 0xffefba91.toInt()), null, Shader.TileMode.CLAMP) })
        fixture.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        var asset: String? = null
        try {
            asset = HostBackgroundImages.import(context, Uri.fromFile(fixture))
            HostBackgroundImages.grant(context, asset)
            // 反转第二轮顺序，减少温度和宿主图片缓存对固定测试顺序的偏差。
            for ((index, preset) in listOf("off", "aurora", "custom", "custom", "aurora", "off").withIndex()) {
                val name = "${index + 1}-$preset"
                prefs.edit().putString(FeaturePreferences.HOST_BACKGROUND_PRESET, preset)
                    .putString(FeaturePreferences.HOST_BACKGROUND_ASSET, asset)
                    .putInt(FeaturePreferences.HOST_BACKGROUND_BLUR, 60)
                    .putInt(FeaturePreferences.HOST_BACKGROUND_SATURATION, 85)
                    .putInt(FeaturePreferences.HOST_BACKGROUND_VEIL, 35)
                    .putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, true).commit()
                shell("am force-stop tv.danmaku.bili")
                shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
                SystemClock.sleep(4500)
                // 收起搜索栏、预加载几行卡片；测量只在同一范围往返，不下拉刷新。
                root("input swipe 540 1650 540 750 400\nsleep 1\ninput swipe 540 1650 540 1050 400\nsleep 1")
                SystemClock.sleep(1500)
                snapshot("$name-start")
                File(output, "$name-memory-before.txt").writeText(shell("dumpsys meminfo tv.danmaku.bili"))
                File(output, "$name-thermal-before.txt").writeText(shell("dumpsys thermalservice"))
                shell("dumpsys gfxinfo tv.danmaku.bili reset")
                val start = SystemClock.elapsedRealtime()
                val frames = StringBuilder()
                repeat(12) {
                    root("input swipe 540 1650 540 1050 400\nsleep 0.15\ninput swipe 540 1050 540 1650 400\nsleep 0.15")
                    frames.append(shell("dumpsys gfxinfo tv.danmaku.bili framestats"))
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                File(output, "$name-frames.txt").writeText(frames.toString())
                val stats = shell("dumpsys gfxinfo tv.danmaku.bili")
                File(output, "$name-summary.txt").writeText("24 swipes; elapsedMs=$elapsed\n$stats")
                File(output, "$name-memory-after.txt").writeText(shell("dumpsys meminfo tv.danmaku.bili"))
                File(output, "$name-thermal-after.txt").writeText(shell("dumpsys thermalservice"))
                assertTrue("No host frames captured", Regex("Total frames rendered: [1-9]").containsMatchIn(stats))
                snapshot("$name-end")
                println("HOST_BACKGROUND_PERF $name: " + stats.lineSequence().take(28).joinToString(" | "))
            }
        } finally {
            prefs.edit().apply { keys.forEach { key -> when (val value = original[key]) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                else -> remove(key)
            } } }.commit()
            asset?.let { context.revokeUriPermission(HostBackgroundImages.uri(it), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                HostBackgroundImages.file(context, it).delete() }
            fixture.delete()
            shell("am force-stop tv.danmaku.bili")
            shell("am start -W -n tv.danmaku.bili/.MainActivityV2")
        }
    }
}
