package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbHeroBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbHeroForeground
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarArtifactBinding
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostGarbChromeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        .createPackageContext("tv.danmaku.bili", 0)

    @Test fun lateArtworkIsClearedWhileNativeCharacterDrawablesSurvive() {
        val context = context
        fun id(name: String) = context.resources.getIdentifier(name, "id", "tv.danmaku.bili").also { check(it != 0) }
        val dock = FrameLayout(context)
        val glass = ColorDrawable(Color.DKGRAY)
        dock.background = glass
        val top = ImageView(context).apply { this.id = id("tabs_bg_view") }
        val bottom = ImageView(context).apply { this.id = id("tab_background") }
        val icon = ImageView(context).apply { this.id = id("tab_svg"); setImageDrawable(ColorDrawable(Color.BLUE)) }
        val character = icon.drawable
        dock.addView(top); dock.addView(bottom); dock.addView(icon)
        val binding = HostTopBarArtifactBinding(dock)
        repeat(3) {
            for (view in listOf(top, bottom)) {
                view.visibility = View.VISIBLE; view.alpha = 1f
                view.setImageDrawable(ColorDrawable(Color.WHITE))
            }
            binding.clear()
            HostBottomBarFxController.stripAllHostArtifacts(dock)
            for (view in listOf(top, bottom)) {
                assertEquals(View.GONE, view.visibility)
                assertNull(view.drawable)
            }
            assertSame(glass, dock.background)
            assertSame(character, icon.drawable)
        }
    }

    @Test fun compactCharacterKeepsItsAspectRatioAndRestoresNativeSizeWithoutGarb() {
        val context = context
        val density = context.resources.displayMetrics.density
        val host = FrameLayout(context)
        val container = FrameLayout(context)
        val tab = FrameLayout(context)
        val normal = FrameLayout(context).apply {
            id = context.resources.getIdentifier("normal_ll", "id", "tv.danmaku.bili")
        }
        val icon = ImageView(context).apply {
            id = context.resources.getIdentifier("tab_svg", "id", "tv.danmaku.bili")
        }
        normal.addView(icon, FrameLayout.LayoutParams(180, 135))
        tab.addView(normal, FrameLayout.LayoutParams(200, 160))
        container.addView(tab); host.addView(container)
        val config = HostBottomBarFxConfig(compact = true, iconOnly = true)
        val accent = 0xFF6E89FF.toInt()
        repeat(3) {
            HostBottomBarFxController.alignTabContent(host, container, density, 4, config,
                HostChromeColors(true, accent, accent))
            assertTrue(icon.layoutParams.height <= 32f * density + 1)
            assertEquals(4f / 3f, icon.layoutParams.width.toFloat() / icon.layoutParams.height, .03f)
        }
        HostBottomBarFxController.alignTabContent(host, container, density, 4, config, HostChromeColors(true, accent))
        assertEquals(180, icon.layoutParams.width)
        assertEquals(135, icon.layoutParams.height)
    }

    @Test fun mineHeroRetainsItsArtworkAndRestoresNativeForegroundOnThemeExit() {
        val context = context
        val root = FrameLayout(context)
        val anchor = View(context)
        val artworkContainer = FrameLayout(context).apply { foreground = ColorDrawable(Color.TRANSPARENT) }
        val hero = ImageView(context).apply {
            id = context.resources.getIdentifier("main_top_bg_imageview", "id", "tv.danmaku.bili")
            check(id != 0)
            setImageDrawable(ColorDrawable(Color.YELLOW))
        }
        artworkContainer.addView(hero)
        artworkContainer.addView(View(context)) // 动态装扮在图片之上还有原生视频子层。
        root.addView(artworkContainer); root.addView(anchor)
        val artwork = hero.drawable
        val foreground = artworkContainer.foreground
        val outline = artworkContainer.outlineProvider
        val layer = artworkContainer.layerType
        val binding = HostGarbHeroBinding(anchor)
        binding.refresh(true)
        assertSame(artwork, hero.drawable)
        assertNotSame(foreground, artworkContainer.foreground)
        assertFalse(artworkContainer.clipToOutline)
        assertEquals(View.LAYER_TYPE_HARDWARE, artworkContainer.layerType)
        binding.refresh(false)
        assertSame(artwork, hero.drawable)
        assertSame(foreground, artworkContainer.foreground)
        assertSame(outline, artworkContainer.outlineProvider)
        assertFalse(artworkContainer.clipToOutline)
        assertEquals(layer, artworkContainer.layerType)
    }

    @Test fun videoAndScrimShareAntialiasedCornerCoverageWithoutErasingTheBackdrop() {
        assertVideoCornerCoverage(dimEnabled = true)
    }

    @Test fun disabledDimmingRetainsVideoBrightnessAndAntialiasedCorners() {
        assertVideoCornerCoverage(dimEnabled = false)
    }

    private fun assertVideoCornerCoverage(dimEnabled: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frameReady = AtomicReference(CountDownLatch(1))
        lateinit var texture: TextureView
        lateinit var hero: FrameLayout
        lateinit var window: android.view.Window
        val width = 320
        val height = 220
        val backdrop = Color.rgb(0, 0, 200)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                val root = FrameLayout(activity).apply { setBackgroundColor(backdrop) }
                // 宿主 Context 提供资源表，模块 Activity 只提供真实硬件窗口与 PixelCopy。
                val hostContext = context
                hero = FrameLayout(hostContext)
                val image = ImageView(hostContext).apply {
                    id = hostContext.resources.getIdentifier("main_top_bg_imageview", "id", "tv.danmaku.bili")
                }
                check(image.id != 0)
                hero.addView(image, FrameLayout.LayoutParams(-1, -1))
                texture = TextureView(activity).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                            paintVideo(surface, Color.WHITE)
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) = Unit
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { frameReady.get().countDown() }
                    }
                }
                hero.addView(texture, FrameLayout.LayoutParams(-1, -1))
                root.addView(hero, FrameLayout.LayoutParams(width, height).apply { leftMargin = 40; topMargin = 260 })
                activity.setContentView(root)
                HostGarbHeroBinding(hero, dimEnabled = dimEnabled).refresh(true)
                check(hero.foreground is HostGarbHeroForeground)
            }
            for (color in listOf(Color.WHITE, Color.YELLOW)) {
                if (color != Color.WHITE) {
                    frameReady.set(CountDownLatch(1))
                    instrumentation.runOnMainSync { paintVideo(checkNotNull(texture.surfaceTexture), color) }
                }
                assertTrue("Video frame did not arrive", frameReady.get().await(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val location = IntArray(2)
                instrumentation.runOnMainSync { hero.getLocationInWindow(location) }
                val bitmap = Bitmap.createBitmap(width + 16, height + 16, Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var result = -1
                PixelCopy.request(window, Rect(location[0] - 8, location[1] - 8,
                    location[0] + width + 8, location[1] + height + 8), bitmap,
                    { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue(copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                try {
                    assertEquals("Mask must preserve the parent backdrop", backdrop, bitmap.getPixel(2, height / 2))
                    val radius = (24f * hero.resources.displayMetrics.density).toInt()
                    var mixed = 0
                    for (y in height - radius until height - 1) {
                        val inside = bitmap.getPixel(8 + width / 2, 8 + y)
                        if (!dimEnabled) for (shift in listOf(16, 8, 0)) {
                            assertEquals("Disabled dimming must retain video brightness",
                                (color ushr shift and 255).toFloat(), (inside ushr shift and 255).toFloat(), 2f)
                        }
                        val red = Color.red(inside)
                        assertEquals("Hardware layer must retain the latest video frame",
                            if (color == Color.WHITE) red.toFloat() else 0f, Color.blue(inside).toFloat(), 2f)
                        for (x in 0 until radius) for (column in listOf(x, width - 1 - x)) {
                            val edge = bitmap.getPixel(8 + column, 8 + y)
                            assertTrue("Bright fringe at ($column,$y)", Color.red(edge) <= red + 2)
                            val coverage = Color.red(edge).toFloat() / red
                            if (coverage > .03f && coverage < .97f) {
                                mixed++
                                val blue = 200f + (Color.blue(inside) - 200f) * coverage
                                assertTrue("Video and scrim have different edge coverage", abs(Color.blue(edge) - blue) <= 4f)
                            }
                        }
                    }
                    assertTrue("Corners must have fractional coverage (mixed=$mixed)", mixed >= 16)
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun paintVideo(texture: SurfaceTexture, color: Int) {
        val surface = Surface(texture)
        try {
            val canvas = surface.lockCanvas(null)
            try { canvas.drawColor(color) } finally { surface.unlockCanvasAndPost(canvas) }
        } finally { surface.release() }
    }
}
