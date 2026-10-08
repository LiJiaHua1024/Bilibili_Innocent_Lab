package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostCopyBubblePlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class HostCopyBubblePlacementTest {
    @Test fun keepsLeadingAlignmentAndRightAndLeftMargins() {
        assertEquals(84f, HostCopyBubblePlacement.left(100, 800, 1080, 16, 8), 0f)
        assertEquals(272f, HostCopyBubblePlacement.left(950, 800, 1080, 16, 8), 0f)
        assertEquals(8f, HostCopyBubblePlacement.left(0, 800, 1080, 16, 8), 0f)
    }

    @Test fun leavesTheOriginalBottomTwentyPercentClearAndFallsBackForStaleAnchors() {
        assertEquals(554f, HostCopyBubblePlacement.top(500, 50, true, 200, 2340, 4, 8), 0f)
        assertEquals(1496f, HostCopyBubblePlacement.top(1800, 50, true, 300, 2340, 4, 8), 0f)
        assertEquals(1572f, HostCopyBubblePlacement.top(2200, 50, true, 300, 2340, 4, 8), 0f)
        assertEquals(990f, HostCopyBubblePlacement.top(3000, 50, false, 200, 2340, 4, 8), 0f)
    }
}
