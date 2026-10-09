package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportResult
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMaterialRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 隔离私有资产和偏好，直接检查普通柔光根背景像素，不修改用户配置。 */
@RunWith(AndroidJUnit4::class)
class FrostedCustomBackgroundInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val customColor = Color.rgb(220, 20, 200)
    private val baseColor = Color.rgb(16, 17, 20)

    private inner class Fixture(corrupt: Boolean = false, bind: Boolean = true, detailed: Boolean = false) : AutoCloseable {
        private val target = instrumentation.targetContext
        private val namespace = "frosted-background-test-" + UUID.randomUUID()
        val directory = File(target.cacheDir, namespace).apply { mkdirs() }
        private val preferenceNames = linkedSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = namespace + "_" + name
                synchronized(preferenceNames) { preferenceNames.add(scoped) }
                return target.getSharedPreferences(scoped, mode)
            }
        }
        val config: com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
        lateinit var renderer: FrostedMaterialRenderer
        lateinit var root: View

        init {
            val input = File(directory, "input.png")
            val bitmap = Bitmap.createBitmap(if (detailed) 480 else 120, if (detailed) 800 else 200, Bitmap.Config.ARGB_8888)
            try {
                if (detailed) {
                    val pixels = IntArray(bitmap.width * bitmap.height) { i ->
                        if (i % bitmap.width / 2 % 2 == 0) Color.BLACK else Color.WHITE
                    }
                    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                } else bitmap.eraseColor(customColor)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            assertTrue(LiquidBackgroundStore.importFromUri(context, Uri.fromFile(input)) is LiquidBackgroundImportResult.Success)
            config = LiquidBackgroundStore.read(context).config
            if (corrupt) {
                val asset = directory.walkTopDown().single { it.isFile && it.name.endsWith(".img") }
                assertTrue(asset.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                asset.writeBytes(byteArrayOf(1, 2, 3))
            }
            instrumentation.runOnMainSync {
                val palette = MonetColors(Color.GREEN,Color.BLACK,Color.CYAN,Color.YELLOW,Color.DKGRAY,baseColor,Color.GRAY)
                renderer = FrostedMaterialRenderer(palette, 1f, backgroundContext = context)
                root = View(context)
                root.layout(0, 0, 480, 800)
                if (bind) assertTrue(renderer.bindRoot(root))
            }
        }

        fun replaceImage(color: Int) {
            val input = File(directory, "replacement.png")
            val bitmap = Bitmap.createBitmap(120,200,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } finally { bitmap.recycle() }
            assertTrue(LiquidBackgroundStore.importFromUri(context,Uri.fromFile(input)) is LiquidBackgroundImportResult.Success)
        }

        fun waitUntilLoaded() {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = renderer.revealFraction == 1f }
                if (ready) return
                SystemClock.sleep(20)
            }
            fail("Frosted background was not delivered before timeout")
        }

        fun pixels(): IntArray {
            var colors = intArrayOf()
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    colors = intArrayOf(bitmap.getPixel(root.width/4, root.height/4),
                        bitmap.getPixel(root.width/2, root.height/2),bitmap.getPixel(root.width*3/4, root.height*3/4))
                } finally { bitmap.recycle() }
            }
            return colors
        }

        fun detailContrast(): Int {
            var contrast = 0
            instrumentation.runOnMainSync {
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    contrast = abs(Color.red(bitmap.getPixel(240, 400)) - Color.red(bitmap.getPixel(242, 400)))
                } finally { bitmap.recycle() }
            }
            return contrast
        }

        override fun close() {
            instrumentation.runOnMainSync { renderer.close(); root.background = null }
            assertTrue(directory.canonicalPath.startsWith(target.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
            synchronized(preferenceNames) {
                preferenceNames.forEach { name ->
                    assertTrue(name.startsWith(namespace + "_"))
                    target.deleteSharedPreferences(name)
                }
            }
        }
    }

    @Test fun ordinaryBackgroundRetainsTwoPixelDetailsAcrossMemoryReleaseAndResume() {
        Fixture(detailed = true).use { fixture ->
            fixture.waitUntilLoaded()
            assertTrue("Native two-pixel lines were blurred", fixture.detailContrast() > 180)
            instrumentation.runOnMainSync { fixture.renderer.onLowMemory() }
            assertTrue(fixture.detailContrast() > 180)
            instrumentation.runOnMainSync { fixture.renderer.stop(); fixture.renderer.resume() }
            fixture.waitUntilLoaded()
            assertTrue(fixture.detailContrast() > 180)
        }
    }

    @Test fun liquidBackgroundRetainsDetailsWhileStandardOpticsStayQuarterResolution() {
        Fixture(bind = false, detailed = true).use { fixture ->
            for (crisp in listOf(false, true)) {
                val bitmap = requireNotNull(LiquidBackgroundStore.decodeBackdrop(fixture.context,
                    fixture.config, 480, 800, baseColor, true))
                val source = LiquidBackdropSource.fromCustomBitmap(bitmap, "detail-fixture", 480, 800, 1f, crisp)
                val rendered = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
                try {
                    source.drawRoot(Canvas(rendered), Rect(0, 0, 480, 800), 255)
                    assertTrue(abs(Color.red(rendered.getPixel(240, 400)) -
                        Color.red(rendered.getPixel(242, 400))) > 180)
                    val sample = LiquidBackdropSizingPolicy.resolve(480, 800)
                    assertEquals(if (crisp) 480 else sample.width, source.refractionWidth)
                    assertEquals(if (crisp) 800 else sample.height, source.refractionHeight)
                } finally { rendered.recycle(); source.discardUnpublished() }
            }
        }
    }

    @Test fun opaqueImageImportPreservesColorValuesWithoutLossyReencoding() {
        Fixture(bind = false).use { fixture ->
            val input = File(fixture.directory, "colors.png")
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(480 * 800) { i ->
                Color.rgb((i * 17) and 255, (i * 37) and 255, (i * 61) and 255)
            }
            try {
                bitmap.setPixels(pixels, 0, 480, 0, 0, 480, 800)
                bitmap.setHasAlpha(false)
                input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            assertTrue(LiquidBackgroundStore.importFromUri(fixture.context, Uri.fromFile(input)) is LiquidBackgroundImportResult.Success)
            val asset = fixture.directory.walkTopDown().single { it.isFile && it.name.endsWith(".img") }
            val decoded = requireNotNull(BitmapFactory.decodeFile(asset.absolutePath))
            try {
                val actual = IntArray(pixels.size)
                decoded.getPixels(actual, 0, 480, 0, 0, 480, 800)
                assertArrayEquals(pixels, actual)
            } finally { decoded.recycle() }
        }
    }

    private fun assertCustom(pixels: IntArray, color: Int = customColor) {
        val expected = ColorUtils.compositeColors(Color.argb(0x28, 0, 0, 0), color)
        for (pixel in pixels) {
            assertTrue("Expected custom background, got ${Integer.toHexString(pixel)}",
                abs(Color.red(pixel)-Color.red(expected))<=3 &&
                abs(Color.green(pixel)-Color.green(expected))<=3 &&
                abs(Color.blue(pixel)-Color.blue(expected))<=3)
        }
    }

    @Test fun customAssetIsVisibleWithTheMaterialYouRenderer() {
        Fixture().use { fixture ->
            fixture.waitUntilLoaded()
            assertEquals(SkinId.MATERIAL_YOU,fixture.renderer.skin)
            assertCustom(fixture.pixels())
        }
    }

    @Test fun memoryPressureKeepsThePictureAndStopResumeReloadsIt() {
        Fixture().use { fixture ->
            fixture.waitUntilLoaded()
            instrumentation.runOnMainSync { fixture.renderer.onLowMemory() }
            assertCustom(fixture.pixels())
            instrumentation.runOnMainSync {
                fixture.renderer.stop()
                fixture.root.layout(0,0,600,900)
            }
            SystemClock.sleep(150)
            assertTrue(fixture.pixels().all { it==baseColor })
            instrumentation.runOnMainSync { fixture.renderer.resume() }
            fixture.waitUntilLoaded()
            assertCustom(fixture.pixels())
        }
    }

    @Test fun returningToAnExistingPageLoadsTheReplacementAsset() {
        Fixture().use { fixture ->
            fixture.waitUntilLoaded()
            instrumentation.runOnMainSync { fixture.renderer.stop() }
            val replacement = Color.rgb(20,220,80)
            fixture.replaceImage(replacement)
            instrumentation.runOnMainSync { fixture.renderer.resume() }
            fixture.waitUntilLoaded()
            assertCustom(fixture.pixels(),replacement)
        }
    }

    @Test fun corruptAssetFallsBackWithoutChangingTheSavedSelection() {
        Fixture(corrupt=true).use { fixture ->
            fixture.waitUntilLoaded()
            val expected = ColorUtils.compositeColors(Color.argb(0x28,0,0,0),customColor)
            assertTrue(fixture.pixels().any { abs(Color.red(it)-Color.red(expected))>10 })
            assertEquals(fixture.config,LiquidBackgroundStore.read(fixture.context).config)
            assertEquals(SkinId.MATERIAL_YOU,fixture.renderer.skin)
        }
    }

    @Test fun completionAfterCloseCannotRestoreTheCustomPicture() {
        Fixture(bind=false).use { fixture ->
            instrumentation.runOnMainSync { assertTrue(fixture.renderer.bindRoot(fixture.root));fixture.renderer.close() }
            SystemClock.sleep(400)
            assertTrue(fixture.pixels().all { it==baseColor })
        }
    }
}
