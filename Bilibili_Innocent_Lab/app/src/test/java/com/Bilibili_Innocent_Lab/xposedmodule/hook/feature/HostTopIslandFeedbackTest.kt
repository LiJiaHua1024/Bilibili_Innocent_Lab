package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class HostTopIslandFeedbackTest {
    @Test fun refreshStartsImmediatelyAndRunsForTheActualNativeRequest() {
        val feedback = HostTopIslandFeedback()
        feedback.clicked(HostTopIslandFeedback.Operation.REFRESH, 100L)
        assertTrue(feedback.refreshing)
        assertTrue(feedback.busy)
        assertTrue(feedback.scale < 1f)
        feedback.updateState(atTop = true, nativeRefreshing = true)
        assertTrue(feedback.needsFrame(10_000L))
        feedback.updateState(atTop = true, nativeRefreshing = false)
        repeat(90) { feedback.advance(1f / 60f) }
        assertFalse(feedback.refreshing)
        assertFalse(feedback.busy)
        assertFalse(feedback.needsFrame(10_100L))
    }

    @Test fun returnToTopHasArrowFeedbackAndDoesNotStartLoading() {
        val feedback = HostTopIslandFeedback()
        feedback.clicked(HostTopIslandFeedback.Operation.TOP, 100L)
        feedback.updateState(atTop = false, nativeRefreshing = false)
        assertTrue(feedback.busy)
        assertFalse(feedback.refreshing)
        assertTrue(feedback.arrowOffset(260L) < -2f)
        feedback.updateState(atTop = true, nativeRefreshing = false)
        assertFalse(feedback.busy)
        assertTrue(feedback.showTopArrow(260L))
        assertFalse(feedback.showTopArrow(500L))
    }

    @Test fun cancelledPressReturnsToNormalWithoutAnActionOrAnEndlessFrameLoop() {
        val feedback = HostTopIslandFeedback()
        feedback.setPressed(true)
        repeat(8) { feedback.advance(1f / 60f) }
        assertTrue(feedback.scale < .98f)
        feedback.setPressed(false)
        repeat(90) { feedback.advance(1f / 60f) }
        assertEquals(1f, feedback.scale, .001f)
        assertFalse(feedback.busy)
        assertFalse(feedback.needsFrame(1000L))
    }

    @Test fun refreshStartedElsewhereAlsoShowsLoadingAndDetachClearsFeedback() {
        val feedback = HostTopIslandFeedback()
        feedback.updateState(atTop = true, nativeRefreshing = true)
        assertTrue(feedback.refreshing)
        feedback.reset()
        assertFalse(feedback.needsFrame(1000L))
        assertEquals(1f, feedback.scale, 0f)
    }
}
