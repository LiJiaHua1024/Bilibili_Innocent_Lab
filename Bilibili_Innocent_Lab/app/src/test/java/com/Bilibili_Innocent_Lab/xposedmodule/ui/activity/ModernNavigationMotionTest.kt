package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class ModernNavigationMotionTest {
    @Test fun continuousProgressAndRtlUseTheSameFourLogicalPages() {
        assertEquals(1.4f, ModernNavigationMotion.physicalSlot(1.4f, 4, false), .00001f)
        assertEquals(1.6f, ModernNavigationMotion.physicalSlot(1.4f, 4, true), .00001f)
        assertEquals(0, ModernNavigationMotion.indexAt(10f, 280f, 4f, 4, false))
        assertEquals(3, ModernNavigationMotion.indexAt(10f, 280f, 4f, 4, true))
        assertEquals(3, ModernNavigationMotion.indexAt(280f, 280f, 4f, 4, false))
        assertEquals(0, ModernNavigationMotion.indexAt(280f, 280f, 4f, 4, true))
        assertEquals(2f, ModernNavigationMotion.scrubPosition(1f, 70f, 70f, 4, false), 0f)
        assertEquals(0f, ModernNavigationMotion.scrubPosition(1f, 70f, 70f, 4, true), 0f)
        assertEquals(3f, ModernNavigationMotion.scrubPosition(1f, 10000f, 70f, 4, false), 0f)
        assertEquals(0f, ModernNavigationMotion.position(Float.NaN, 4), 0f)
    }

    @Test fun fiveItemHostDocksClickAndScrubAcrossAllFiveSlots() {
        // 宿主默认 5 tab(首页/动态/+/会员购/我的):滑块与点击必须覆盖索引 0..4。
        assertEquals(4f, ModernNavigationMotion.position(9f, 5), .00001f)
        assertEquals(4f, ModernNavigationMotion.physicalSlot(4f, 5, false), .00001f)
        assertEquals(0f, ModernNavigationMotion.physicalSlot(4f, 5, true), .00001f)
        assertEquals(4, ModernNavigationMotion.indexAt(350f, 280f, 4f, 5, false))
        assertEquals(0, ModernNavigationMotion.indexAt(350f, 280f, 4f, 5, true))
        assertEquals(0, ModernNavigationMotion.indexAt(10f, 280f, 4f, 5, false))
        assertEquals(4, ModernNavigationMotion.indexAt(10f, 280f, 4f, 5, true))
        // 从索引 1 拖一大段:非 RTL 到最右"我的"(4),RTL 钳到最左(0)。
        assertEquals(4f, ModernNavigationMotion.scrubPosition(1f, 10000f, 70f, 5, false), 0f)
        assertEquals(0f, ModernNavigationMotion.scrubPosition(1f, 10000f, 70f, 5, true), 0f)
        // 轻点最右侧 tab(index 4)在 5 tab 下必须可选中——恢复 5 键后"不可点击"的直接回归。
        val gesture = ModernNavigationGesture()
        gesture.begin(4, false)
        assertEquals(4, gesture.finish(false, 4f, 4, 5))
        // 超出 MAX_ITEMS 的异常 count 仍被钳制/拒绝。
        assertEquals(6f, ModernNavigationMotion.position(6f, 9), .00001f)
        assertEquals(-1, ModernNavigationMotion.indexAt(10f, 280f, 4f, 9, false))
    }

    @Test fun fourDirectionMovementHasOneFourDpVectorBudgetIncludingDiagonalRelease() {
        for (x in listOf(-10000f, -50f, 0f, 50f, 10000f)) for (y in listOf(-10000f, -50f, 0f, 50f, 10000f)) {
            val scale = ModernNavigationMotion.displacementScale(x, y, 4f, 48f)
            assertTrue(sqrt(x * x + y * y) * scale <= 4.00001f)
            assertTrue(scale >= 0f)
            val springClamp = ModernNavigationMotion.travelClampScale(x, y, 4f)
            assertTrue(sqrt(x * x + y * y) * springClamp <= 4.00001f)
        }
        assertEquals(0f, ModernNavigationMotion.displacementScale(Float.NaN, 1f, 4f, 48f), 0f)
        assertEquals(1f, ModernNavigationMotion.lensScaleX(0f), 0f)
        assertEquals(1.055f, ModernNavigationMotion.lensScaleX(2f), .00001f)
        assertEquals(1.07f, ModernNavigationMotion.lensScaleY(2f), .00001f)
    }

    @Test fun onlyReleasedHorizontalLensScrubsCommitAndCancellationNeverDoes() {
        val gesture = ModernNavigationGesture()
        gesture.begin(1, true)
        assertFalse(gesture.move(2f, 1f, 8f))
        assertTrue(gesture.move(30f, 3f, 8f))
        assertFalse(gesture.move(80f, 3f, 8f))
        assertEquals(ModernNavigationIntent.SCRUB, gesture.intent)
        assertEquals(2, gesture.finish(false, 2.4f, 1, 4))
        assertNull(gesture.finish(false, 2.4f, 1, 4))
        gesture.begin(1, true)
        gesture.move(80f, 0f, 8f)
        assertNull(gesture.finish(true, 3f, 3, 4))
        gesture.begin(1, true)
        gesture.move(80f, 0f, 8f)
        gesture.cancel()
        assertNull(gesture.finish(false, 3f, 3, 4))
    }

    @Test fun verticalAndOffLensDragsOnlyReboundWhileStationaryTapsSelectTheirOwnSlot() {
        val gesture = ModernNavigationGesture()
        gesture.begin(2, true)
        gesture.move(3f, -40f, 8f)
        assertEquals(ModernNavigationIntent.ELASTIC, gesture.intent)
        assertNull(gesture.finish(false, 1f, 2, 4))
        gesture.begin(2, false)
        gesture.move(-40f, 3f, 8f)
        assertNull(gesture.finish(false, 1f, 1, 4))
        gesture.begin(2, false)
        assertEquals(2, gesture.finish(false, 1f, 2, 4))
        gesture.begin(2, false)
        assertNull(gesture.finish(false, 1f, -1, 4))
    }

    @Test fun analyticSpringPreservesInterruptionPositionAndVelocityAndConverges() {
        for (start in listOf(-4f, 0f, .4f, 4f)) for (velocity in listOf(-20f, 0f, 20f)) {
            val spring = ModernNavigationSpring(start, 0f, velocity)
            assertEquals(start, spring.value(0f), .000001f)
            assertEquals(velocity, spring.velocity(0f), .0001f)
            assertTrue(abs(spring.value(1f)) < .0001f)
            val time = .075f
            val retarget = ModernNavigationSpring(spring.value(time), 1f, spring.velocity(time))
            assertEquals(spring.value(time), retarget.value(0f), .000001f)
            assertEquals(spring.velocity(time), retarget.velocity(0f), .0001f)
            assertTrue(abs(retarget.value(1f) - 1f) < .0001f)
        }
    }
}
