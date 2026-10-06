package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostBottomBarLayoutTest {
    @Test fun iconOnlyNarrowsTheBarAndCompactMakesTheReductionStronger() {
        val normal = HostBottomBarFxConfig()
        for (mode in listOf(normal, normal.copy(compact = true))) {
            assertEquals(16f, mode.horizontalMarginDp(393f, 5), 0f)
        }
        val icons = normal.copy(iconOnly = true)
        assertEquals((393f - 32f) * 0.88f, 393f - icons.horizontalMarginDp(393f, 5) * 2f, 0.001f)
        val combined = normal.copy(compact = true, iconOnly = true)
        val margin = combined.horizontalMarginDp(393f, 5)
        assertEquals((393f - 32f) * 0.84f, 393f - margin * 2f, 0.001f)
        assertTrue(margin > icons.horizontalMarginDp(393f, 5))
        assertEquals(44f, combined.heightDp, 0f)
        assertTrue(combined.copy(liquidGlass = false).horizontalMarginDp(393f, 5) > 0f)
    }

    @Test fun narrowScreensPreserveTabSpaceWithoutExpandingPastTheOriginalWidth() {
        val combined = HostBottomBarFxConfig(compact = true, iconOnly = true)
        val minimumWidth = 5 * 44f + 8f
        for (mode in listOf(combined, combined.copy(compact = false))) {
            for (parentWidth in listOf(260f, 280f, 320f)) {
                val width = parentWidth - mode.horizontalMarginDp(parentWidth, 5) * 2f
                assertTrue(width >= minimumWidth)
                assertTrue(width <= parentWidth - 32f)
            }
            assertEquals(16f, mode.horizontalMarginDp(200f, 5), 0f)
        }
    }

    @Test fun layoutModesReduceHeightIndependentlyAndTogether() {
        val normal = HostBottomBarFxConfig(liquidGlass = false, touchGlow = false)
        val compact = normal.copy(compact = true)
        val icons = normal.copy(iconOnly = true)
        val combined = compact.copy(iconOnly = true)
        assertEquals(64f, normal.heightDp, 0f)
        assertTrue(compact.heightDp < normal.heightDp)
        assertTrue(icons.heightDp < compact.heightDp)
        assertTrue(combined.heightDp < icons.heightDp)
        assertTrue(combined.heightDp >= 44f)
        for (mode in listOf(normal, compact, icons, combined)) {
            assertEquals(mode.heightDp, mode.copy(liquidGlass = true, touchGlow = true).heightDp, 0f)
        }
    }
}
