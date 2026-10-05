package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopFusionPolicyTest {

    private val density = 2.75f
    private val statusBar = 90

    @Test fun bandCoversStatusBarCapsuleRowAndFadeTail() {
        val height = HostTopFusionPolicy.bandHeight(statusBar, density)
        // 状态栏 + (56 + 44)dp，440dpi 下即 90 + 275px。
        assertEquals(statusBar + ((HostTopFusionPolicy.HOLD_DP + HostTopFusionPolicy.FADE_DP) * density).toInt(), height)

        val hold = HostTopFusionPolicy.holdFraction(statusBar, density)
        // 满强度区下沿（屏幕坐标）= 状态栏下沿 + 56dp，正是顶栏胶囊收起后的下边缘。
        assertEquals(statusBar + HostTopFusionPolicy.HOLD_DP * density, hold * height, 1f)
        assertTrue("满强度区必须落在带子内部", hold > 0f && hold < 1f)
    }

    @Test fun zeroStatusBarDegradesGracefully() {
        val height = HostTopFusionPolicy.bandHeight(0, density)
        assertTrue(height > 0)
        val hold = HostTopFusionPolicy.holdFraction(0, density)
        assertTrue(hold > 0f && hold < 1f)
        assertEquals(1f, HostTopFusionPolicy.holdFraction(0, 0f), 0f)
    }

    @Test fun fadeWeightHoldsThenDecaysMonotonicallyToZero() {
        val hold = 0.6f
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(0f, hold), 0f)
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(hold, hold), 0f)
        assertEquals(0f, HostTopFusionPolicy.fadeWeight(1f, hold), 1e-6f)

        var previous = 1f
        var fraction = 0f
        while (fraction <= 1f) {
            val weight = HostTopFusionPolicy.fadeWeight(fraction, hold)
            assertTrue("单调 @ $fraction", weight <= previous + 1e-6f)
            // C¹ 收口：两端导数都为 0，与上下两侧的不渐隐区相接时才不会有折线。
            assertTrue("无跳变 @ $fraction", previous - weight < 0.02f)
            previous = weight
            fraction += 1f / 256f
        }
        assertEquals(0f, previous, 1e-6f)
    }

    @Test fun fadeWeightIsASmoothstepSymmetricAboutTheMidpoint() {
        val hold = 0f
        assertEquals(0.5f, HostTopFusionPolicy.fadeWeight(0.5f, hold), 1e-6f)
        // 前后对称：smoothstep 的经典性质，用来锁住"不是线性插值"这件事。
        for (u in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(
                HostTopFusionPolicy.fadeWeight(u, hold),
                1f - HostTopFusionPolicy.fadeWeight(1f - u, hold),
                1e-6f
            )
        }
    }
}
