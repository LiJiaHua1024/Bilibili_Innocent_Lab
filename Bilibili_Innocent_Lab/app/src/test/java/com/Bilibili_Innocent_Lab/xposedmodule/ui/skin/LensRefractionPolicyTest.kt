package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test

class LensRefractionPolicyTest {
    @Test fun sampleScaleKeepsTheBitmapBudgetBounded() {
        for ((w, h) in listOf(1 to 1, 1080 to 240, 1440 to 320, 3840 to 2160, 20000 to 20000)) {
            val scale = LensRefractionPolicy.sampleScale(w, h)
            assertTrue(scale >= LensRefractionPolicy.MIN_SCALE)
            val pixels = (w / scale).toLong().coerceAtLeast(1) * (h / scale).coerceAtLeast(1)
            assertTrue("$w x $h → scale $scale", pixels <= LensRefractionPolicy.MAX_SAMPLE_PIXELS)
        }
        assertEquals(LensRefractionPolicy.MIN_SCALE, LensRefractionPolicy.sampleScale(0, 10))
    }

    @Test fun lensIsOddMonotonicAndContinuous() {
        for ((gain, push) in listOf(
            LensRefractionPolicy.CENTER_GAIN_X to LensRefractionPolicy.RIM_PUSH_X,
            LensRefractionPolicy.CENTER_GAIN_Y to LensRefractionPolicy.RIM_PUSH_Y
        )) {
            assertEquals(0f, LensRefractionPolicy.lens(0f, gain, push), 1e-6f)
            var previous = LensRefractionPolicy.lens(-1f, gain, push)
            var u = -1f
            while (u < 1f) {
                u += 1f / 512f
                val value = LensRefractionPolicy.lens(u, gain, push)
                assertTrue(value.isFinite())
                assertTrue("单调 @ $u", value >= previous - 1e-6f)
                assertTrue("连续 @ $u", value - previous < 0.02f)
                assertEquals("奇函数 @ $u", -value, LensRefractionPolicy.lens(-u, gain, push), 1e-5f)
                previous = value
            }
            // 中心放大：内区比例 < 1；外沿推出：边缘越出 1。
            assertTrue(LensRefractionPolicy.lens(0.3f, gain, push) < 0.3f)
            assertTrue(LensRefractionPolicy.lens(1f, gain, push) > 1f)
            assertEquals(LensRefractionPolicy.lens(1f, gain, push), LensRefractionPolicy.lens(7f, gain, push), 0f)
        }
    }

    @Test fun remapPreservesConstantImagesAndStaysInsideTheSource() {
        val sw = 40
        val sh = 16
        val margin = 4
        val source = IntArray(sw * sh) { 0x80402010.toInt() }
        val out = IntArray(90 * 30)
        LensRefractionPolicy.remap(source, sw, sh, margin, out, 90, 30)
        assertTrue(out.all { it == 0x80402010.toInt() })

        val gradient = IntArray(sw * sh) { i -> 0xFF000000.toInt() or ((i % sw) * 6) }
        LensRefractionPolicy.remap(gradient, sw, sh, margin, out, 90, 30)
        for (i in 0 until 90) {
            val a = out[i] ushr 24
            val b = out[i] and 0xFF
            assertEquals(0xFF, a)
            assertTrue(b in 0..(sw - 1) * 6)
        }
        assertTrue(runCatching { LensRefractionPolicy.remap(source, sw, sh, margin, out, 0, 30) }.isFailure)
    }

    @Test fun premultiplyRoundTripsOpaqueAndDropsFullyTransparentColor() {
        val pixels = intArrayOf(0xFF10A0F0.toInt(), 0x00FFFFFF, 0x80FF0000.toInt())
        LensRefractionPolicy.premultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        LensRefractionPolicy.unpremultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        assertEquals(0x80, pixels[2] ushr 24)
        assertTrue((pixels[2] shr 16 and 0xFF) >= 0xFE)
    }

    @Test fun illuminateLiftsPremultipliedChannelsAndKeepsAlpha() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0x80FF0000.toInt(), 0x00000000, 0xFFF0F0F0.toInt())
        LensRefractionPolicy.illuminate(pixels)
        // 提亮只动预乘 RGB：alpha 通道原样保留，全透明像素不产出颜色。
        assertEquals(0xFF, pixels[0] ushr 24)
        assertTrue((pixels[0] ushr 16 and 255) > 0x10)
        assertTrue((pixels[0] and 255) > 0x30)
        assertEquals(0x80, pixels[1] ushr 24)
        assertEquals(0, pixels[2])
        // 接近满亮的像素被钳到 255，不溢出回绕。
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    @Test fun onlyFloatingTopBarAndModalUseTheLiveBackdrop() {
        for (dark in listOf(false, true)) {
            for (role in SurfaceRole.values()) {
                val style = ModernMaterialPolicy.surface(role, dark)
                val expected = role == SurfaceRole.FLOATING || role == SurfaceRole.TOP_BAR ||
                    role == SurfaceRole.MODAL
                assertEquals(role.name, expected, style.live)
            }
        }
    }

    @Test fun remapFlatIsAPlainResizeOfTheInnerRegion() {
        val sw = 48
        val sh = 20
        val margin = 5
        // 竖直渐变源：无折射时输出应逐行等值，且始终落在内区（外沿 ring 完全不参与）。
        val source = IntArray(sw * sh) { i -> 0xFF000000.toInt() or ((i / sw) * 10) }
        val out = IntArray(120 * 30)
        LensRefractionPolicy.remapFlat(source, sw, sh, margin, out, 120, 30)
        for (y in 0 until 30) {
            val row = out.copyOfRange(y * 120, y * 120 + 120)
            assertTrue("行内等值 @ $y", row.all { it == row[0] })
            assertTrue("内区 @ $y: ${row[0] and 0xFF}", (row[0] and 0xFF) in 0..(sh - 1) * 10)
        }
        val values = (0 until 30).map { out[it * 120] and 0xFF }
        assertEquals("单调", values.sorted(), values)
        // 双线性取样落在内区 [margin-0.5, sh-margin-0.5)：永远不会取到外沿 ring 深处。
        assertTrue("只在內区取值: $values", values.all { it in (margin - 1) * 10..(sh - margin) * 10 })
        assertTrue("覆盖整个内区: $values", values.last() - values.first() > (sh - 2 * margin - 2) * 10)
    }

    @Test fun fadeVerticallyHoldsThenDecaysToTransparentSmoothly() {
        val width = 4
        val height = 100
        val pixels = IntArray(width * height) { 0xFF3366CC.toInt() }
        LensRefractionPolicy.fadeVertically(pixels, width, height, hold = 0.5f, end = 1f)

        val alpha = { y: Int -> pixels[y * width] ushr 24 }
        // 满强度区原样保留；末行完全透明（融合带下沿必须与未模糊的内容严丝合缝）。
        assertEquals(0xFF, alpha(0))
        assertEquals(0xFF, alpha(49))
        assertEquals(0, alpha(height - 1))
        // 单调，且没有任何一行跳变：100 行上的 smoothstep 单行最大斜率约 3/100 × 255 ≈ 8。
        var previous = alpha(50)
        for (y in 51 until height) {
            val current = alpha(y)
            assertTrue("单调 @ $y", current <= previous)
            assertTrue("无跳变 @ $y: $previous → $current", previous - current <= 9)
            previous = current
        }
        // RGB 原样保留；全透明行也不再泄露颜色。
        assertEquals(0x003366CC, pixels[(height - 1) * width])
        assertEquals(0xFF3366CC.toInt(), pixels[10 * width])
    }

    @Test fun fadeVerticallyKeepsZeroAlphaAndRejectsMismatchedBuffers() {
        val pixels = intArrayOf(0x00FF0000, 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt())
        LensRefractionPolicy.fadeVertically(pixels, 1, 4, hold = 0f, end = 0f)
        assertEquals(0x00FF0000, pixels[0]) // end ≤ hold：不渐隐
        LensRefractionPolicy.fadeVertically(pixels, 1, 4, hold = 0.75f, end = 1f)
        assertEquals(0, pixels[0] ushr 24) // 原来就全透明的像素保持全透明
        assertEquals(0xFF, pixels[1] ushr 24) // 满强度区原样
        assertEquals(0xFF, pixels[2] ushr 24)
        // 末行落在曲线中点（0.875 → smoothstep(0.5) = 0.5），alpha 恰好减半。
        assertEquals(128, pixels[3] ushr 24)
        assertTrue(runCatching { LensRefractionPolicy.fadeVertically(pixels, 1, 5, 0f, 1f) }.isFailure)
    }
}
