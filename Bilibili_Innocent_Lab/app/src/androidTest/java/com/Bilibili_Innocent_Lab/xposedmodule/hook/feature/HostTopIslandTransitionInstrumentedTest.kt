package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandPageActions
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceDiagnostics
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 检查实际窗口的第一帧，不能等后台采样完成后才宣称收起没有闪动。 */
@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class HostTopIslandTransitionInstrumentedTest {
    @Test fun gpuCollapseKeepsTheCurrentSampleOnItsFirstFrame() = firstFrame(false)
    @Test fun softwareCollapseKeepsTheCurrentSampleOnItsFirstFrame() = firstFrame(true)
    @SdkSuppress(minSdkVersion = 33)
    @Test fun liquidCollapseKeepsTheCurrentSampleOnItsFirstFrame() = firstFrame(false, true)

    private fun firstFrame(software: Boolean, liquid: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var content: View
            lateinit var window: Window
            lateinit var dock: FrameLayout
            lateinit var scope: HostSurfaceScope
            lateinit var island: HostTopIslandBinding
            var probe: (() -> Unit)? = null
            scenario.onActivity { activity ->
                window = activity.window
                val parent = FrameLayout(activity)
                content = View(activity).apply { setBackgroundColor(Color.RED) }
                parent.addView(content, FrameLayout.LayoutParams(-1, -1))
                dock = object : FrameLayout(activity) {
                    override fun draw(canvas: Canvas) {
                        super.draw(canvas)
                        probe?.also { probe = null }?.invoke()
                    }
                }
                parent.addView(dock, FrameLayout.LayoutParams(600, 120).apply {
                    leftMargin = 40; topMargin = 200
                })
                scope = HostSurfaceScope(dock, HostChromeColors(false, Color.MAGENTA),
                    if (software) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.AUTO,
                    HostChromeAppearance(material = if (liquid) HostGlassMaterial.LIQUID else HostGlassMaterial.SOFT))
                scope.floating(dock, false, 60f)
                island = checkNotNull(HostTopIslandBinding.attach(dock, null, 1f, null, scope,
                    Color.MAGENTA, HostTopIslandPageActions(parent, null)))
                activity.setContentView(parent)
                scope.attach(dock, content)
            }
            try {
                instrumentation.waitForIdleSync()
                // 每轮先换掉展开时的内容，再分别覆盖首次收起与原位置重复收起。
                for (color in listOf(Color.BLUE, Color.RED, Color.BLUE)) {
                    instrumentation.runOnMainSync {
                        content.setBackgroundColor(color)
                        scope.onVisualMovement()
                    }
                    SystemClock.sleep(250)
                    for (repeat in 0..1) {
                        val latch = CountDownLatch(1)
                        lateinit var before: LumenSurfaceDiagnostics
                        var after: LumenSurfaceDiagnostics? = null
                        instrumentation.runOnMainSync {
                            before = checkNotNull(scope.diagnostics())
                            probe = { after = scope.diagnostics(); latch.countDown() }
                            setState(island, dock, collapsed = true, progress = .04f)
                        }
                        assertTrue("First collapse frame did not draw", latch.await(3, TimeUnit.SECONDS))
                        val drawn = checkNotNull(after)
                        assertEquals("First collapse frame flashed its fallback tint (software=$software, repeat=$repeat)",
                            before.staticDraws, drawn.staticDraws)
                        assertTrue("First collapse frame lost its live background",
                            if (software) drawn.softwareDraws > before.softwareDraws else drawn.gpuDraws > before.gpuDraws)
                        val pixel = sample(window, dock)
                        val difference = Color.red(pixel) - Color.blue(pixel)
                        assertTrue("Collapse reused the content from an earlier position: ${Integer.toHexString(pixel)}",
                            if (color == Color.RED) difference > 40 else difference < -40)
                        instrumentation.runOnMainSync { setState(island, dock, collapsed = false, progress = 0f) }
                        instrumentation.waitForIdleSync()
                    }
                }
            } finally {
                instrumentation.runOnMainSync { probe = null; scope.close() }
            }
        }
    }

    private fun sample(window: Window, dock: View): Int {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        try {
            val latch = CountDownLatch(1)
            var result = -1
            val location = IntArray(2)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                dock.getLocationInWindow(location)
                PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("Window capture timed out", latch.await(3, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            return bitmap.getPixel(location[0] + 325, location[1] + 60)
        } finally { bitmap.recycle() }
    }

    private fun setState(island: HostTopIslandBinding, dock: View, collapsed: Boolean, progress: Float) {
        dock.visibility = View.VISIBLE
        HostTopIslandBinding::class.java.getDeclaredField("collapsed").apply {
            isAccessible = true; setBoolean(island, collapsed)
        }
        HostTopIslandBinding::class.java.getDeclaredField("animating").apply {
            isAccessible = true; setBoolean(island, collapsed)
        }
        HostTopIslandBinding::class.java.getDeclaredMethod("applyProgress", Float::class.javaPrimitiveType).apply {
            isAccessible = true; invoke(island, progress)
        }
    }
}
