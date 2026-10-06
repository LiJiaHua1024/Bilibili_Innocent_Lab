package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTrackLayoutTest {
    @Test fun ringsStayInsideTheReservedColumnForNarrowAndLargeFontWindows() {
        for (width in listOf(0f, 1f, 40f, 80f, 120f, 200f, 280f, 440f)) {
            for (density in listOf(0.75f, 1f, 2.75f, 4f)) {
                for (font in listOf(1f, 1.5f, 2.5f)) {
                    val layout = ReplyTopologyTrackLayout.resolve(width * density, density, font)
                    val outer = layout.ringRadius + layout.strokeWidth * 0.5f
                    for (depth in listOf(0, 1, 7, 100, 5_000, Int.MAX_VALUE)) {
                        assertTrue(layout.laneX(depth) - outer >= -0.001f)
                        assertTrue(layout.laneX(depth) + outer <= layout.trackWidth + 0.001f)
                    }
                    assertTrue(layout.trackWidth <= layout.width)
                    assertTrue(layout.maxLane in 0..7)
                }
            }
        }
    }

    @Test fun narrowAndLargeFontLayoutsReduceCapacityBeforeTheyReduceTextSpace() {
        val normal = ReplyTopologyTrackLayout.resolve(280f, 1f, 1f)
        val narrow = ReplyTopologyTrackLayout.resolve(160f, 1f, 1f)
        val enlarged = ReplyTopologyTrackLayout.resolve(280f, 1f, 2f)
        assertEquals(7, normal.maxLane)
        assertTrue(narrow.maxLane < normal.maxLane)
        assertTrue(enlarged.maxLane < normal.maxLane)
        assertTrue(narrow.width - narrow.trackWidth >= narrow.width * 0.65f)
    }

    @Test fun compressionIsOnlyAVisualDecisionAndHugeDepthDoesNotOverflow() {
        val layout = ReplyTopologyTrackLayout.resolve(280f, 1f, 1f)
        assertFalse(layout.compresses(7))
        assertTrue(layout.compresses(8))
        assertEquals(layout.laneX(8), layout.laneX(Int.MAX_VALUE), 0f)
    }

    @Test fun panelSizeNeverExceedsAResizedParentEvenWhenMinimumIsLarger() {
        for (available in listOf(1, 80, 180, 280, 800)) {
            val result = ReplyTopologyPanelSizing.dimension(available, 1f, 0.86f, 280, 440)
            assertTrue(result in 1..available)
        }
        assertEquals(180, ReplyTopologyPanelSizing.dimension(180, 1f, 0.86f, 280, 440))
        assertTrue(ReplyTopologyPanelSizing.dimension(440, 1f, 0.86f, 280, 440) > 180)
    }
}
