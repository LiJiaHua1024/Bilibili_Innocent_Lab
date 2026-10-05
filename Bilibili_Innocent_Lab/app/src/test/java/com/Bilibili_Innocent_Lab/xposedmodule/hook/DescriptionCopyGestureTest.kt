package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionCopyGestureTest {
    private fun gesture(slop: Float = 8f) = DescriptionCopyGesture().apply {
        begin(1_000L, 7, 100f, 200f, slop)
    }

    @Test fun stationaryPressClaimsOnceAtExistingTimeout() {
        val gesture = gesture()
        assertFalse(gesture.claim(1_000L, 1_399L))
        assertTrue(gesture.claim(1_000L, 1_400L))
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun shortTapAndSmallJitterStayWithHost() {
        val gesture = gesture()
        assertFalse(gesture.sample(1_000L, 7, 1, 103f, 204f))
        assertFalse(gesture.claim(1_000L, 1_100L))
        assertTrue(gesture.isPending)
    }

    @Test fun slowScrollBelowOldSixtyPixelsCancelsAllLongPressSources() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 7, 1, 100f, 209f))
        assertFalse(gesture.claim(1_000L, 1_400L)) // 定时器
        assertFalse(gesture.claim(1_000L, 1_500L)) // 系统监听器
        assertFalse(gesture.claim(1_000L, 2_000L)) // UP 兜底
    }

    @Test fun returningToDownPointDoesNotRearmAfterScroll() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 7, 1, 120f, 200f))
        assertFalse(gesture.sample(1_000L, 7, 1, 100f, 200f))
        assertFalse(gesture.claim(1_000L, 1_600L))
    }

    @Test fun historicalExcursionCancelsEvenWhenLatestPointIsInsideSlop() {
        val gesture = gesture()
        for (y in listOf(202f, 215f, 200f)) gesture.sample(1_000L, 7, 1, 100f, y)
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun upCoordinatesAlsoRejectUndeliveredMovement() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 7, 1, 100f, 230f))
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun diagonalMovementUsesSameRadialBudgetAsAxialMovement() {
        val gesture = gesture()
        assertFalse(gesture.sample(1_000L, 7, 1, 104f, 206f))
        assertTrue(gesture.sample(1_000L, 7, 1, 106f, 206f))
    }

    @Test fun deviceSlopControlsCancellationRatherThanPixelMagicNumber() {
        val small = gesture(8f)
        val large = gesture(24f)
        assertTrue(small.sample(1_000L, 7, 1, 100f, 216f))
        assertFalse(large.sample(1_000L, 7, 1, 100f, 216f))
        assertTrue(large.claim(1_000L, 1_400L))
    }

    @Test fun parentCancellationIsIrreversibleWithinSameStream() {
        val gesture = gesture()
        assertTrue(gesture.cancel(1_000L))
        assertFalse(gesture.cancel(1_000L))
        assertTrue(gesture.matches(1_000L))
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun secondPointerCancelsCandidateWithoutChoosingAnotherFinger() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 7, 2, 100f, 200f))
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun missingOriginalPointerFailsClosed() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 9, 1, 100f, 200f))
        assertFalse(gesture.claim(1_000L, 1_500L))
    }

    @Test fun eventsFromAnotherStreamDoNotCancelOrClaimCurrentGesture() {
        val gesture = gesture()
        assertFalse(gesture.sample(999L, 7, 1, 1_000f, 1_000f))
        assertFalse(gesture.cancel(999L))
        assertFalse(gesture.claim(999L, 1_500L))
        assertTrue(gesture.claim(1_000L, 1_400L))
    }

    @Test fun replacementGestureRejectsOldCallbackAndOldTerminalEvent() {
        val gesture = gesture()
        gesture.begin(2_000L, 9, 0f, 0f, 8f)
        assertFalse(gesture.claim(1_000L, 3_000L))
        assertFalse(gesture.cancel(1_000L))
        assertFalse(gesture.claim(2_000L, 2_100L))
        assertTrue(gesture.claim(2_000L, 2_400L))
    }

    @Test fun completedLongPressCannotBeClaimedAgainByMovementOrAnotherEntry() {
        val gesture = gesture()
        assertTrue(gesture.claim(1_000L, 1_400L))
        assertFalse(gesture.sample(1_000L, 7, 1, 100f, 500f))
        assertFalse(gesture.cancel(1_000L))
        assertFalse(gesture.claim(1_000L, 1_600L))
    }

    @Test fun terminationRestoresNonTouchAndNextGestureEligibility() {
        val gesture = gesture()
        gesture.cancel(1_000L)
        gesture.reset()
        assertFalse(gesture.isTracking)
        assertFalse(gesture.claim(1_000L, 2_000L))
        gesture.begin(2_000L, 7, 0f, 0f, 8f)
        assertTrue(gesture.claim(2_000L, 2_400L))
    }

    @Test fun invalidSamplesCannotOpenPopup() {
        val gesture = gesture()
        assertTrue(gesture.sample(1_000L, 7, 1, Float.NaN, 200f))
        assertFalse(gesture.claim(1_000L, 1_500L))
        gesture.begin(2_000L, 7, 0f, 0f, Float.POSITIVE_INFINITY)
        assertFalse(gesture.isPending)
        assertFalse(gesture.claim(2_000L, 2_500L))
    }

    @Test fun bubbleDismissRestoresNonTouchEligibilityAfterHandledGesture() {
        val gesture = gesture()
        assertTrue(gesture.claim(1_000L, 1_400L))
        gesture.finishHandled()
        assertFalse(gesture.isTracking)
    }

    @Test fun oldBubbleDismissCannotDiscardNewGestureDuringExitAnimation() {
        val gesture = gesture()
        assertTrue(gesture.claim(1_000L, 1_400L))
        gesture.begin(2_000L, 9, 0f, 0f, 8f)
        gesture.finishHandled()
        assertTrue(gesture.isPending)
        assertTrue(gesture.claim(2_000L, 2_400L))
    }
}
