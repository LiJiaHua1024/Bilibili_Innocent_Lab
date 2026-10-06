package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopFusionPolicyTest {

    private val density = 2.75f
    private val statusBar = 90
    private val basePadding = 154 // 顶栏胶囊行 44dp + 上下各 6dp，440dpi

    /** 首页收起态：顶栏容器顶边 = 状态栏下沿。 */
    private val collapsedTop = statusBar

    /** 首页展开态实测值。 */
    private val expandedTop = 244

    @Test fun dockingReservesTheWholeHeaderAtTheTopAndNoneAwayFromIt() {
        val range = HostTopFusionPolicy.DOCK_RANGE_DP * density
        assertEquals(1f, HostTopFusionPolicy.dockingProgress(0f, range), 0f)
        assertEquals(1f, HostTopFusionPolicy.dockingProgress(-80f, range), 0f)
        assertEquals(0.5f, HostTopFusionPolicy.dockingProgress(range / 2f, range), 1e-6f)
        assertEquals(0f, HostTopFusionPolicy.dockingProgress(range, range), 0f)
        assertEquals(0f, HostTopFusionPolicy.dockingProgress(Float.POSITIVE_INFINITY, range), 0f)
    }

    @Test fun dockingFollowsDistanceContinuouslyInBothDirectionsWithoutEndpointJumps() {
        val range = HostTopFusionPolicy.DOCK_RANGE_DP * density
        var previous = 1f
        for (i in 0..256) {
            val distance = range * i / 256f
            val progress = HostTopFusionPolicy.dockingProgress(distance, range)
            assertTrue(progress <= previous)
            assertTrue(previous - progress < 0.006f)
            assertEquals(1f - progress, HostTopFusionPolicy.dockingProgress(range - distance, range), 1e-6f)
            previous = progress
        }
        // 两端一像素手势只带来亚像素占位变化，返回/离开顶部不会突然跳一截。
        assertTrue((1f - HostTopFusionPolicy.dockingProgress(1f, range)) * expandedTop < 0.01f)
        assertTrue(HostTopFusionPolicy.dockingProgress(range - 1f, range) * expandedTop < 0.01f)
    }

    @Test fun fadeEndsAboveTheRestingContentSoTheFirstRowStaysSharp() {
        for (containerTop in listOf(collapsedTop, expandedTop)) {
            val restTop = HostTopFusionPolicy.contentRestTop(containerTop, basePadding)
            val height = HostTopFusionPolicy.bandHeight(restTop)
            val hold = HostTopFusionPolicy.holdFraction(statusBar, height)
            val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)

            val gap = HostTopFusionPolicy.CONTENT_GAP_DP * density
            assertTrue("收口必须在内容静止顶边之上: 容器 $containerTop, 收口 ${end * height}, 顶边 $restTop", end * height <= restTop - gap + 1f)
            // 静止顶边处的权重严格为 0：第一排卡片停在静止位置时完全清晰。
            assertEquals(0f, HostTopFusionPolicy.fadeWeight(restTop / height.toFloat(), hold, end), 1e-6f)
            assertEquals(1f, HostTopFusionPolicy.fadeWeight(0f, hold, end), 0f)
        }
    }

    @Test fun holdKeepsTheWholeStatusBarAtFullStrength() {
        val restTop = HostTopFusionPolicy.contentRestTop(collapsedTop, basePadding)
        val height = HostTopFusionPolicy.bandHeight(restTop)
        val hold = HostTopFusionPolicy.holdFraction(statusBar, height)
        assertEquals(statusBar.toFloat(), hold * height, 1f)
        // 状态栏下沿到渐隐收口之间是干净的单调递减，没有任何满强度平台或跳变。
        val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)
        assertTrue("渐隐必须有实际长度", end - hold > 0.1f)
    }

    @Test fun bandAlwaysCoversTheFadeEnd() {
        for (containerTop in listOf(0, collapsedTop, expandedTop, 600)) {
            val restTop = HostTopFusionPolicy.contentRestTop(containerTop, basePadding)
            val height = HostTopFusionPolicy.bandHeight(restTop)
            val end = HostTopFusionPolicy.fadeEndFraction(restTop, density, statusBar, height)
            assertTrue("收口必须落在带内: $restTop", end <= 1f)
            assertTrue("带高必须盖过静止顶边", height >= restTop)
        }
    }

    @Test fun degenerateGeometryStaysSane() {
        assertEquals(1f, HostTopFusionPolicy.holdFraction(statusBar, 0), 0f)
        assertEquals(1f, HostTopFusionPolicy.fadeEndFraction(0, density, statusBar, 0), 0f)
        // 状态栏比带子还高时满强度区被夹到整个带子，不会出现越界或倒挂。
        val hold = HostTopFusionPolicy.holdFraction(1000, 100)
        assertEquals(1f, hold, 0f)
        val end = HostTopFusionPolicy.fadeEndFraction(50, density, 1000, 100)
        assertTrue(end >= hold)
    }

    @Test fun fadeWeightHoldsThenDecaysMonotonicallyToZero() {
        val hold = 0.2f
        val end = 0.9f
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(0f, hold, end), 0f)
        assertEquals(1f, HostTopFusionPolicy.fadeWeight(hold, hold, end), 0f)
        assertEquals(0f, HostTopFusionPolicy.fadeWeight(end, hold, end), 1e-6f)
        assertEquals(0f, HostTopFusionPolicy.fadeWeight(1f, hold, end), 0f)

        var previous = 1f
        var fraction = 0f
        while (fraction <= 1f) {
            val weight = HostTopFusionPolicy.fadeWeight(fraction, hold, end)
            assertTrue("单调 @ $fraction", weight <= previous + 1e-6f)
            // C¹ 收口：两端导数都为 0，与上下两侧相接时才不会有折线。
            assertTrue("无跳变 @ $fraction", previous - weight < 0.03f)
            previous = weight
            fraction += 1f / 256f
        }
        assertEquals(0f, previous, 1e-6f)
    }

    @Test fun fadeWeightIsASmoothstepSymmetricAboutTheMidpoint() {
        val hold = 0f
        val end = 1f
        assertEquals(0.5f, HostTopFusionPolicy.fadeWeight(0.5f, hold, end), 1e-6f)
        for (u in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(
                HostTopFusionPolicy.fadeWeight(u, hold, end),
                1f - HostTopFusionPolicy.fadeWeight(1f - u, hold, end),
                1e-6f
            )
        }
    }
}
