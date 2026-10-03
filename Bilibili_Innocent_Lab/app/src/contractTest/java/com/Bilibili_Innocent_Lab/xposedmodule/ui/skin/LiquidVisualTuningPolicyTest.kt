package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract

class LiquidVisualTuningPolicyTest {

    /**
     * 未设自定义图时 Liquid 的自动 underlay 必须与标准磨砂皮肤共用 [AmbientBackdropScene]：
     * 两种材质下用户看到的是同一个 Monet 氛围背景。
     *
     * 场景与两个调用方都不得再叠颗粒：可见底图按 0.2~0.25 倍窗口尺寸光栅化后放大铺满屏幕，
     * 逐像素噪声会放大成 4~5px 的斑块状纹理，实机观感就是"背景很脏"（2026-10-03 用户反馈）。
     * 色带由 DITHER_FLAG 负责；场景本身只有低频渐变与光晕，放大不会糊。
     */
    @Test
    fun `auto backdrop shares the clean ambient scene with the frosted skin`() {
        val liquid = source("liquid/LiquidBackdropSource")
        val frosted = source("material/FrostedMaterialRenderer")
        val scene = source("background/AmbientBackdropScene")

        assertTrue(scene.contains("fun paint(canvas: Canvas, palette: MonetColors"))
        assertFalse(scene.contains("addGrain"))
        assertTrue(liquid.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertFalse(liquid.contains("addGrain"))
        assertTrue(frosted.contains("AmbientBackdropScene.paint(canvas, palette"))
        assertFalse(frosted.contains("addGrain"))
    }

    @Test
    fun `saturation stays near the source palette`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.saturation in .94f..1f)
        }
    }

    @Test
    fun `gpu glass is lighter than translucent fallback`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.cardGlassAlpha < tuning.cardFallbackAlpha)
            assertTrue(tuning.modalGlassAlpha < tuning.modalFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha < tuning.motionFallbackAlpha)
            assertTrue(tuning.cardGlassAlpha in .24f..0.34f)
            assertTrue(tuning.modalGlassAlpha in .4f..0.9f)
            assertTrue(tuning.cardFallbackAlpha < 0.8f)
            assertTrue(tuning.modalFallbackAlpha < 0.95f)
        }
    }

    @Test
    fun `modal remains more opaque than card`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)

            assertTrue(tuning.modalGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.modalFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionGlassAlpha > tuning.cardGlassAlpha)
            assertTrue(tuning.motionGlassAlpha <= tuning.modalGlassAlpha)
            assertTrue(tuning.motionFallbackAlpha > tuning.cardFallbackAlpha)
            assertTrue(tuning.motionFallbackAlpha < tuning.modalFallbackAlpha)
        }
    }

    private fun source(name: String): String = SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$name.kt")
}
