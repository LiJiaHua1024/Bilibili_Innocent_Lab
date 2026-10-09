package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ScrollSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshBatch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceRefreshState
import org.junit.Assert.*
import org.junit.Test

class ScrollSurfaceScopeTest {
    private class Node(val parent: Node? = null)

    @Test fun onlyDescendantsOfTheActualScrollHostAreAffected() {
        val root = Node()
        val page = Node(root)
        val anotherPage = Node(root)
        assertTrue(ScrollSurfaceScope.contains(Node(Node(page)), page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(page, page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(root, page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(Node(anotherPage), page) { it.parent })
        assertFalse(ScrollSurfaceScope.contains(Node(), page) { it.parent })
    }

    @Test fun deepHierarchyHasNoArbitraryRejectionThreshold() {
        val host = Node()
        var child = host
        repeat(512) { child = Node(child) }
        assertTrue(ScrollSurfaceScope.contains(child, host) { it.parent })
    }

    @Test fun lateScrollNotificationIsConsumedBeforeDrawAndDrawResetsItsPhase() {
        val batch = LiquidRefreshBatch()
        batch.mark(contentChanged = false)
        assertEquals(LiquidRefreshBatch.POSITION, batch.take())
        batch.beforeDraw()
        assertTrue(batch.isAfterPreDraw)
        batch.mark(contentChanged = false)
        assertEquals(LiquidRefreshBatch.POSITION, batch.take())
        batch.drawn()
        assertFalse(batch.isAfterPreDraw)
        assertEquals(0, batch.take())
    }

    @Test fun synchronousScrollDoesNotConsumeOtherPendingContentOrAdvanceTheRecordedOrigin() {
        val batch = LiquidRefreshBatch()
        val state = LiquidSurfaceRefreshState()
        batch.mark(contentChanged = true)
        batch.mark(contentChanged = true, captureOnly = true)
        var recorded = 0
        var actual = 0
        fun refresh(): Boolean = state.shouldRefresh(true, actual != recorded, false)
        assertFalse(refresh())
        actual = 12
        assertTrue(refresh())
        // Invalidating is not recording. A second offset in this same frame still needs a redraw.
        actual = 24
        assertTrue(refresh())
        assertEquals(0, recorded)
        recorded = actual
        repeat(120) { assertFalse(refresh()) }
        assertEquals(LiquidRefreshBatch.CONTENT or LiquidRefreshBatch.CAPTURE, batch.take())
    }

    @Test fun scopedVisibilityStillRefreshesHiddenSurfacesOnReentry() {
        val state = LiquidSurfaceRefreshState()
        assertFalse(state.shouldRefresh(false, false, false))
        assertTrue(state.shouldRefresh(true, false, false))
        assertFalse(state.shouldRefresh(true, false, false))
    }
}
