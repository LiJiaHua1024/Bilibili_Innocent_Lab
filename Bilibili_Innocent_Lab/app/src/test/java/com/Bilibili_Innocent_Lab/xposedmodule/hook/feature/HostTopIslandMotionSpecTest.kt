package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticSpringAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HostTopIslandMotionSpecTest {
    private val width = 992f
    private val height = 121f
    private val density = 2.75f

    @Test fun springStartsGentlyAndKeepsAVisibleDecelerationTail() {
        for (compact in listOf(false, true)) {
            val target = if (compact) 1f else 0f
            val spring = ElasticSpringAxis(1f - target)
            val damping = if (compact) HostTopIslandMotionSpec.DAMPING_RATIO else HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO
            val stiffness = if (compact) HostTopIslandMotionSpec.COLLAPSE_STIFFNESS else HostTopIslandMotionSpec.EXPAND_STIFFNESS
            repeat(3) { spring.advance(1f / 60f, target, stiffness, damping) }
            assertTrue("前 50ms 不能突然走掉大半行程", abs(spring.value - (1f - target)) < .15f)
            var frames = 3
            while (!spring.atRest(target, .5f / (width - height)) && frames < 120) {
                spring.advance(1f / 60f, target, stiffness, damping)
                frames++
            }
            assertTrue("收尾必须保留减速时间，且不无限爬行: $frames", frames in 36..84)
            val before = HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density)
            val end = HostTopIslandMotionSpec.shellWidth(target, width, height, density)
            assertTrue("静止落位不能产生像素跳变", abs(before - end) <= .5f)
        }
    }

    @Test fun reboundCannotSqueezeTheBallFlatOrGrowBeyondTheSideMargins() {
        for (step in -20..220) {
            val progress = step / 200f
            val shell = HostTopIslandMotionSpec.shellWidth(progress, width, height, density)
            assertTrue(shell >= height * .93f)
            assertTrue(shell <= width + 12f * density)
            val vertical = HostTopIslandMotionSpec.verticalInset(progress, width, height, density)
            assertTrue(height - 2f * vertical > 0f)
            assertTrue(abs(vertical) < 2f * density)
        }
    }

    @Test fun expansionHasAVisibleOvershootAndThenReturnsToItsFinalSize() {
        val spring = ElasticSpringAxis(1f)
        var maxStretch = 0f
        repeat(90) {
            spring.advance(1f / 60f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS,
                HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO)
            maxStretch = maxOf(maxStretch, HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density) - width)
        }
        assertTrue("展开要有肉眼可见的舒展回弹", maxStretch > 8f * density)
        assertEquals(width, HostTopIslandMotionSpec.shellWidth(spring.value, width, height, density), .5f)
    }

    @Test fun nativeTextAndCompactGlyphHandOffWithoutOverlapping() {
        assertEquals(1f, HostTopIslandMotionSpec.contentAlpha(0f), 0f)
        assertEquals(0f, HostTopIslandMotionSpec.glyphAlpha(0f), 0f)
        assertEquals(0f, HostTopIslandMotionSpec.contentAlpha(1f), 0f)
        assertEquals(1f, HostTopIslandMotionSpec.glyphAlpha(1f), 0f)
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(0f, HostTopIslandMotionSpec.contentAlpha(progress) * HostTopIslandMotionSpec.glyphAlpha(progress), 0f)
        }
    }

    @Test fun retargetingContinuesTheCurrentMomentumAndThenReturnsSmoothly() {
        val spring = ElasticSpringAxis()
        repeat(10) { spring.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        val position = spring.value
        val velocity = spring.velocity
        assertTrue(velocity > 0f)
        spring.advance(1f / 120f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS, HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO)
        assertTrue("换目标不能立刻丢弃收缩惯性", spring.value > position)
        assertTrue(abs(spring.value - position) < .04f)
        repeat(168) { spring.advance(1f / 120f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS, HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO) }
        assertEquals(0f, spring.value, .001f)
    }

    @Test fun springTrajectoryIsTheSameAtSixtyAndOneHundredTwentyHertz() {
        val sixty = ElasticSpringAxis()
        val highRefresh = ElasticSpringAxis()
        repeat(30) { sixty.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        repeat(60) { highRefresh.advance(1f / 120f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS, HostTopIslandMotionSpec.DAMPING_RATIO) }
        assertEquals(sixty.value, highRefresh.value, .00001f)
        assertEquals(sixty.velocity, highRefresh.velocity, .00001f)
    }

    @Test fun expansionCanReverseIntoCollapseBeforeItsSpringTailFinishes() {
        val spring = ElasticSpringAxis(1f)
        repeat(20) { spring.advance(1f / 60f, 0f, HostTopIslandMotionSpec.EXPAND_STIFFNESS,
            HostTopIslandMotionSpec.EXPAND_DAMPING_RATIO) }
        assertTrue(!spring.atRest(0f, .5f / (width - height)))
        val position = spring.value
        spring.advance(1f / 120f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS,
            HostTopIslandMotionSpec.DAMPING_RATIO)
        assertTrue("反向不能跳到另一端或清空动画", abs(spring.value - position) < .03f)
        repeat(90) { spring.advance(1f / 60f, 1f, HostTopIslandMotionSpec.COLLAPSE_STIFFNESS,
            HostTopIslandMotionSpec.DAMPING_RATIO) }
        assertEquals(1f, spring.value, .001f)
    }
}
