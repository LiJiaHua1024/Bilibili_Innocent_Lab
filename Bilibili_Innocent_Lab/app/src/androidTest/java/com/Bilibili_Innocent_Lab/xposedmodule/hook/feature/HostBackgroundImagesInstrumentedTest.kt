package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundImagesInstrumentedTest {
    @Test fun zeroBlurPreservesImageAndBlurSoftensAnEdgeWithoutDarkBorders() {
        val source = Bitmap.createBitmap(360, 120, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(360 * 120) { if (it % 360 < 180) Color.RED else Color.BLUE }
        source.setPixels(pixels, 0, 360, 0, 0, 360, 120)
        val clear = HostBackgroundImages.process(source, HostBackgroundConfig(blur = 0, saturation = 100))
        val soft = HostBackgroundImages.process(source, HostBackgroundConfig(blur = 30, saturation = 100))
        assertEquals(Color.RED, clear.getPixel(179, 60))
        assertEquals(Color.BLUE, clear.getPixel(180, 60))
        val edge = soft.getPixel(179, 60)
        assertTrue(Color.red(edge) in 1..254 && Color.blue(edge) in 1..254)
        assertEquals(Color.RED, soft.getPixel(0, 60))
        assertEquals(Color.BLUE, soft.getPixel(359, 60))
        assertFalse("Fully opaque PNG pixels should use the opaque rendering path", soft.hasAlpha())
        assertEquals(255, Color.alpha(edge))
        source.recycle(); clear.recycle(); soft.recycle()
    }

    @Test fun zeroSaturationIsGrayscaleAndOutputIsBounded() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val result = HostBackgroundImages.process(source, HostBackgroundConfig(blur = 0, saturation = 0))
        assertEquals(960, result.width); assertEquals(480, result.height)
        val pixel = result.getPixel(0, 0)
        assertEquals(Color.red(pixel), Color.green(pixel)); assertEquals(Color.green(pixel), Color.blue(pixel))
        source.recycle(); result.recycle()
    }

    @Test fun processingPreservesRealImageTransparency() {
        val source = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(0x808050c0.toInt()) }
        val result = HostBackgroundImages.process(source, HostBackgroundConfig(blur = 0, saturation = 100))
        assertTrue(result.hasAlpha())
        assertEquals(128, Color.alpha(result.getPixel(20, 40)))
        source.recycle(); result.recycle()
    }

    @Test fun nightModeActuallyDarkensPresetsAndMissingImageFallsBack() {
        val config = HostBackgroundConfig(HostBackgroundPreset.CUSTOM)
        val background = HostBackgroundDrawable(config)
        val target = Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
        background.setBounds(0, 0, 300, 600)
        background.draw(Canvas(target))
        val light = target.getPixel(150, 300)
        target.eraseColor(Color.TRANSPARENT)
        background.draw(Canvas(target))
        assertEquals("Repeated frames must remain opaque and stable", light, target.getPixel(150, 300))
        background.night = true
        background.draw(Canvas(target))
        val dark = target.getPixel(150, 300)
        assertTrue(Color.red(dark) < Color.red(light))
        assertEquals(255, Color.alpha(dark))
        target.recycle()
    }
}
