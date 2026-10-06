package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyLoadPriorityTest {
    @Test fun visibleRowsPrecedeNearestNeighboursInBothDirections() {
        assertArrayEquals(intArrayOf(40, 41, 42, 39, 43, 38, 44, 37, 45, 36, 46),
            ReplyTopologyLoadPriority.rows(10_000, 40, 42))
        assertArrayEquals(intArrayOf(80, 81, 79, 82, 78, 83, 77, 84, 76, 85),
            ReplyTopologyLoadPriority.rows(10_000, 80, 81))
    }

    @Test fun aMovedViewportDropsOldRowsInsteadOfFinishingOldWorkFirst() {
        val old = ReplyTopologyLoadPriority.rows(10_000, 40, 42)
        val fresh = ReplyTopologyLoadPriority.rows(10_000, 900, 902)
        assertEquals(900, fresh.first())
        assertTrue(fresh.none { it in old })
    }

    @Test fun edgesAndExtremeInputsStayBoundedAndNeverDuplicateRows() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), ReplyTopologyLoadPriority.rows(5, 0, 2))
        assertArrayEquals(intArrayOf(3, 4, 2, 1, 0), ReplyTopologyLoadPriority.rows(5, 3, 4))
        val huge = ReplyTopologyLoadPriority.rows(Int.MAX_VALUE, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(96, huge.size)
        assertEquals(huge.size, huge.distinct().size)
        assertTrue(ReplyTopologyLoadPriority.rows(0, 0, 0).isEmpty())
        assertTrue(ReplyTopologyLoadPriority.rows(100, -1, -1).isEmpty())
        assertTrue(ReplyTopologyLoadPriority.rows(100, 100, 101).isEmpty())
    }

    @Test fun horizontalNeighboursCannotBeClassifiedAsVisibleAndPartialCardsCan() {
        assertTrue(ReplyTopologyLoadPriority.intersects(-10.0, 20.0, 30.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(110.0, 20.0, 200.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(0.0, 110.0, 80.0, 200.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(Double.NaN, 0.0, 30.0, 80.0, 100.0, 100.0))
        assertFalse(ReplyTopologyLoadPriority.intersects(0.0, 0.0, -1.0, 80.0, 100.0, 100.0))
    }
}
