package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundSizingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackgroundSizingPolicyTest {

    @Test
    fun `normalization preserves aspect ratio within hard limits`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(8000, 4000)

        assertEquals(4096, size.width)
        assertEquals(2048, size.height)
        assertTrue(size.width.toLong() * size.height.toLong() <=
            LiquidBackgroundSizingPolicy.MAX_NORMALIZED_PIXELS)
    }

    @Test
    fun `modern phone photo keeps enough pixels for a full-hd portrait window`() {
        val size = LiquidBackgroundSizingPolicy.resolveNormalizedSize(4032, 3024)

        assertEquals(4000, size.width)
        assertEquals(3000, size.height)
        // 中心裁剪到 9:20 后仍是缩小（scale <= 1），呈现位图不必再放大照片。
        assertTrue(LiquidBackgroundSizingPolicy.centerCrop(
            size.width, size.height, 1080, 2400
        ).scale <= 1f)
    }

    @Test
    fun `portrait source center crops horizontally into portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 1200,
            sourceHeight = 2400,
            targetWidth = 360,
            targetHeight = 800
        )

        assertEquals(1f / 3f, transform.scale, 0.0001f)
        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    @Test
    fun `landscape source center crops horizontally for portrait target`() {
        val transform = LiquidBackgroundSizingPolicy.centerCrop(
            sourceWidth = 2400,
            sourceHeight = 1200,
            targetWidth = 360,
            targetHeight = 800
        )

        assertTrue(transform.translateX < 0f)
        assertEquals(0f, transform.translateY, 0.0001f)
    }

    @Test
    fun `decode sample remains power of two without undershooting target`() {
        val sample = LiquidBackgroundSizingPolicy.decodeSampleSize(4096, 2048, 500, 400)

        assertEquals(4, sample)
        assertTrue(4096 / sample >= 500)
        assertTrue(2048 / sample >= 400)
    }

    @Test
    fun `declared image bomb is rejected before decode`() {
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(16_384, 16_384))
        assertTrue(!LiquidBackgroundSizingPolicy.isSupportedDeclaredSize(20_000, 100))
    }
}
