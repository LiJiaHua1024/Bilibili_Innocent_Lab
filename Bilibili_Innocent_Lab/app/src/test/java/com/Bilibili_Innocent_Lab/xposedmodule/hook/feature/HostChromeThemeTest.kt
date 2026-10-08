package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostChromeThemeTest {
    private val accent = 0xFFFF6699.toInt()

    @Test fun manualHostThemeOverridesOppositeSystemMode() {
        assertTrue(HostChromeColors.resolve(true, false, accent).dark)
        assertFalse(HostChromeColors.resolve(false, true, accent).dark)
    }

    @Test fun unavailableHostApiFallsBackToSystemAndBrandAccent() {
        for (systemDark in listOf(false, true)) {
            val colors = HostChromeColors.resolve(null, systemDark, null)
            assertEquals(systemDark, colors.dark)
            assertEquals(accent, colors.accent)
        }
    }

    @Test fun hostAccentSurvivesDayNightAndNeutralSurfacesFollowHostMode() {
        val light = HostChromeColors.resolve(false, true, accent).palette()
        val dark = HostChromeColors.resolve(true, false, accent).palette()
        assertEquals(accent, light.primary)
        assertEquals(accent, dark.primary)
        assertEquals(0xFFFCFBFE.toInt(), light.surface)
        assertEquals(0xFF24252A.toInt(), dark.surface)
        assertEquals(0xFF101114.toInt(), dark.background)
    }

    @Test fun foregroundOnHostAccentRemainsReadableForLightAndDarkSkins() {
        for (color in listOf(accent, 0xFF151515.toInt(), 0xFFFFDD00.toInt())) {
            val palette = HostChromeColors(false, color).palette()
            assertEquals(if (color == 0xFF151515.toInt()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                palette.onPrimary)
        }
    }

}
