package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostCopyBubble
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenEffectPreset
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.widget.BubbleDrawable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostUiParityInstrumentedTest {
    @Test fun shortCopyBubbleWrapsTextAndKeepsTextOutsideScaledShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var bubble: HostCopyBubble
            lateinit var text: TextView
            lateinit var root: ViewGroup
            val closed = CountDownLatch(1)
            var closeCount = 0
            scenario.onActivity { activity ->
                val page = FrameLayout(activity)
                val anchor = TextView(activity).apply { this.text = "anchor" }
                page.addView(anchor, FrameLayout.LayoutParams(200, 80).apply { leftMargin = 40; topMargin = 200 })
                activity.setContentView(page)
                text = TextView(activity).apply { this.text = "短文字 😀"; textSize = 15f; maxLines = 12 }
                page.post {
                    bubble = HostCopyBubble(activity, anchor, text, true, true)
                    bubble.onClosed = { closeCount++; closed.countDown() }
                    bubble.show()
                    root = text.parent as ViewGroup
                }
            }
            try {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(450L)
                instrumentation.runOnMainSync {
                    assertTrue(bubble.dialog.isShowing)
                    val shell = root.getChildAt(0)
                    assertTrue(shell.background is BubbleDrawable)
                    assertSame(root, text.parent)
                    assertEquals(1f, text.scaleX, 0f)
                    assertEquals(1f, text.alpha, .001f)
                    assertTrue(text.isTextSelectable)
                    assertTrue("Short text must not use the presenter's fixed screen width", shell.width < root.width * .6f)
                    assertEquals(1f, (shell.background as BubbleDrawable).scale, .001f)
                    assertEquals(1f, (shell.background as BubbleDrawable).strokeAlpha, .001f)
                    root.performClick()
                    assertFalse(text.isTextSelectable)
                }
                assertTrue("Outside dismissal timed out", closed.await(3, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { bubble.dialog.dismiss(); assertEquals(1, closeCount) }
            } finally { instrumentation.runOnMainSync { runCatching { bubble.dialog.dismiss() } } }
        }
    }

    @Test fun explicitEdgeColorsRoundTripWhileOriginalPresetsKeepDefaultStroke() {
        val colors = LumenSurfaceOptions(edgeTopColor = 0x8CFFFFFF.toInt(), edgeBottomColor = 0x20FFFFFF, backdropOpacity = .5f,
            sampling = com.lumen.coacervation.engine.host.LumenSurfaceSampling(blurRadiusDp = 17f, softwareBlurRadiusDp = 10f,
                fadeCurve = com.lumen.coacervation.engine.host.LumenSurfaceFadeCurve.LINEAR))
        val restored = LumenEffectPreset.fromJson(LumenEffectPreset(surface = colors).toJson()).surface
        assertEquals(colors.edgeTopColor, restored.edgeTopColor)
        assertEquals(colors.edgeBottomColor, restored.edgeBottomColor)
        assertEquals(.5f, restored.backdropOpacity, 0f)
        assertEquals(17f, restored.sampling.blurRadiusDp, 0f)
        assertEquals(10f, requireNotNull(restored.sampling.softwareBlurRadiusDp), 0f)
        assertEquals(colors.sampling.fadeCurve, restored.sampling.fadeCurve)
        val original = LumenEffectPreset.fromJson(LumenEffectPreset().toJson()).surface
        assertNull(original.edgeTopColor)
        assertNull(original.edgeBottomColor)
        assertEquals(1f, original.backdropOpacity, 0f)
        assertNull(original.sampling.softwareBlurRadiusDp)
    }

    @Test fun darkChromeCompensatesBrightContentAndReleasesItsProbe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scope: HostSurfaceScope
            lateinit var source: View
            lateinit var surface: View
            lateinit var page: FrameLayout
            lateinit var window: android.view.Window
            val location = IntArray(2)
            scenario.onActivity { activity ->
                window = activity.window
                page = FrameLayout(activity)
                source = View(activity).apply { setBackgroundColor(Color.WHITE) }
                page.addView(source, FrameLayout.LayoutParams(-1, -1))
                surface = View(activity)
                page.addView(surface, FrameLayout.LayoutParams(600, 150).apply { leftMargin = 40; topMargin = 200 })
                activity.setContentView(page)
                scope = HostSurfaceScope(surface, HostChromeColors(true, Color.MAGENTA))
                scope.surface(surface, HostSurfaceStyle.floating(true, 20f))
                scope.attach(surface, source)
            }
            try {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { surface.getLocationInWindow(location); location[0] += 300; location[1] += 75 }
                var pixel = Color.WHITE
                val deadline = SystemClock.uptimeMillis() + 5000L
                do {
                    val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                    val ready = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(window, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    pixel = bitmap.getPixel(location[0], location[1]); bitmap.recycle()
                    if (Color.red(pixel) < 100) break
                    SystemClock.sleep(50L)
                } while (SystemClock.uptimeMillis() < deadline)
                assertTrue("Bright content remained visible through dark native chrome: ${Integer.toHexString(pixel)}", Color.red(pixel) < 100)
                instrumentation.runOnMainSync {
                    scope.detach()
                    assertNull(surface.background)
                    source.setBackgroundColor(Color.RED)
                    scope.attach(surface, source)
                    assertTrue(scope.owns(surface))
                }
            } finally { instrumentation.runOnMainSync { scope.close() } }
        }
    }
}
