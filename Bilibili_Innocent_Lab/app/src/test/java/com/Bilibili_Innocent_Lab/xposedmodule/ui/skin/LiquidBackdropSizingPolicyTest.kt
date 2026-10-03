package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidBackdropSizingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackdropSizingPolicyTest {

    @Test
    fun `normal display uses quarter resolution`() {
        val size = LiquidBackdropSizingPolicy.resolve(1080, 2400)

        assertEquals(270, size.width)
        assertEquals(600, size.height)
        assertEquals(648_000L, size.byteCount)
    }

    @Test
    fun `fractional quarter dimensions round up`() {
        val size = LiquidBackdropSizingPolicy.resolve(101, 203)

        assertEquals(26, size.width)
        assertEquals(51, size.height)
    }

    @Test
    fun `very large display stays within two mebibytes`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 16_000)

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
        assertTrue(size.width.toLong() * size.height.toLong() <= 524_288L)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    @Test
    fun `large wide display preserves aspect ratio while using the budget`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 4_000)

        assertEquals(4.0, size.width.toDouble() / size.height.toDouble(), 0.02)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    @Test
    fun `custom photo presentation keeps the window pixels`() {
        val sample = LiquidBackdropSizingPolicy.resolve(1080, 2400)
        val presentation = LiquidBackdropSizingPolicy.resolvePresentation(1080, 2400)

        // 呈现按窗口原生像素：照片被放大铺满屏幕就是"图片变糊"的来源。
        assertEquals(1080, presentation.width)
        assertEquals(2400, presentation.height)
        assertTrue(presentation.width.toLong() * presentation.height.toLong() <=
            LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS)
        // 光学采样仍是同一次窗口的 0.25x，两条预算不合并。
        assertEquals(270, sample.width)
        assertEquals(600, sample.height)
        assertTrue(sample.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(presentation.byteCount > sample.byteCount)
    }

    @Test
    fun `huge presentation shrinks proportionally into the pixel budget`() {
        val size = LiquidBackdropSizingPolicy.resolvePresentation(16_000, 4_000)

        assertEquals(4.0, size.width.toDouble() / size.height.toDouble(), 0.02)
        val pixels = size.width.toLong() * size.height.toLong()
        assertTrue(pixels <= LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS)
        assertTrue(pixels >= LiquidBackdropSizingPolicy.MAX_PRESENTATION_PIXELS * 9 / 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero dimension is rejected`() {
        LiquidBackdropSizingPolicy.resolve(0, 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero dimension is rejected for presentation too`() {
        LiquidBackdropSizingPolicy.resolvePresentation(100, 0)
    }
}
