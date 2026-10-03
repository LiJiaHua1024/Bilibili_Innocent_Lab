package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时档的折射输入必须与实时截屏同清晰度。
 *
 * "没有实时截屏可用"的那几段（起播、位移抑制、采集挂起）绑的是稳定底图；它若退回 20dp 模糊
 * 过的光学副本，抑制→解除的换源就成了"组件先糊后清晰"的硬切——2026-10-03 用户报告的
 * "切标签页后各组件样式突变"正是它。本契约盯住三件事：清晰档的选择、`backdropScale` 按折射
 * 输入的位图尺寸算、以及效果档到该开关的传导。
 */
class LiquidRefractionSourceWiringTest {

    @Test
    fun `crisp refraction swaps only the refraction input`() {
        val source = source("liquid/LiquidBackdropSource")

        // 只在清晰档且两份位图确实不同时替换；自动氛围底图/实时截屏两者同源，恒等于旧行为。
        assertTrue(source.contains(
            "private val refractionBitmap = if (crispRefraction && opticalBitmap !== bitmap) " +
                "bitmap else opticalBitmap"
        ))
        assertTrue(source.contains(
            "BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)"
        ))
        // 抑制遮罩/抑制底图/对话框取样仍吃光学副本：那条路要的正是被过滤过的底图。
        assertTrue(source.contains(
            "BitmapShader(opticalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {"
        ))
    }

    @Test
    fun `refraction scale follows the bound bitmap instead of the presentation size`() {
        val backend = source("liquid/LiquidRefractionBackendApi33")

        // content.eval 吃位图像素坐标：比例按呈现尺寸算会把模糊副本错位 4 倍。
        assertTrue(backend.contains("source.refractionWidth.toFloat() / source.fullWidth.toFloat()"))
        assertTrue(backend.contains("source.refractionHeight.toFloat() / source.fullHeight.toFloat()"))
    }

    /**
     * 静止态玻璃内部采到的主要内容是**抑制替换区**（截图里玻璃自己的区域被换成了"玻璃背后的
     * 画面"）。替换区若用 20dp 模糊的光学副本，位移期刚给过清晰观感、一停下来就只剩一片糊，
     * 用户读作"从 Liquid Glass 变成小米式磨砂"（2026-10-03 报告）。替换必须与折射输入同为清晰档。
     */
    @Test
    fun `suppression replacement fills with the presentation bitmap`() {
        val source = source("liquid/LiquidBackdropSource")
        val suppressor = source("liquid/LiquidFeedbackSuppressor")

        val cached = source.after("fun drawSuppressionBackdrop(").before("\n    }\n")
        assertTrue(cached.contains("canvas.drawBitmap(bitmap, null, bounds, rootPaint)"))
        val masked = source.after("fun drawSuppressionBackdropMasked(").before("\n    }\n")
        assertTrue(masked.contains("bitmap.width.toFloat()"))
        assertTrue(masked.contains("suppressionShader.setLocalMatrix"))

        assertTrue(suppressor.contains("drawSuppressionBackdrop(scaleCanvas, scaleBounds, 255)"))
        assertTrue(suppressor.contains("drawSuppressionBackdropMasked("))
        // 模糊副本不再进抑制链，只剩对话框取样（面板刻意不透视下层内容）与标清档折射输入。
        assertFalse(suppressor.contains("drawOpticalBackdrop"))
        assertTrue(source.contains(
            "BitmapShader(opticalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {"
        ))
    }

    @Test
    fun `realtime profile asks the custom backdrop for crisp refraction`() {
        val renderer = source("liquid/LiquidActivityRenderer")

        assertTrue(renderer.contains(
            "crispRefraction = effectProfile == LiquidEffectProfile.REALTIME_CAPTURE"
        ))
    }

    private fun source(name: String): String =
        SourceContract.read("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$name.kt")
}
