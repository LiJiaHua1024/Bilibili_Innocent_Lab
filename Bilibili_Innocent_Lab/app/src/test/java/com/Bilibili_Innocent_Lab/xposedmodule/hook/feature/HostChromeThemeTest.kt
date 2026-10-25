package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
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

    @Test fun reusedThemeValuesAndPalettesMatchTheOriginalResolution() {
        for (host in listOf(true, false, null)) for (system in listOf(true, false)) {
            for (accent in listOf(null, this.accent, 0xFF151515.toInt(), 0xFFFFDD00.toInt())) {
                for (garb in listOf(null, this.accent, 0xFF8A8A8A.toInt())) {
                    val base = HostChromeColors(host ?: system, accent ?: this.accent)
                    val expected = garb?.let { base.copy(accent = it, garbAccent = it) } ?: base
                    val actual = HostChromeColors.resolve(host, system, accent, garb)
                    assertEquals(expected, actual)
                    assertEquals(expected.palette(), actual.palette())
                    assertSame(actual, HostChromeColors.resolve(host, system, accent, garb, actual))
                }
            }
        }
    }

    @Test fun stableFramesReuseColorsWhileEveryVisualInputChangeTakesEffectImmediately() {
        val original = HostChromeColors.resolve(false, false, accent)
        val palette = original.palette()
        repeat(1_000) {
            val next = HostChromeColors.resolve(false, false, accent, previous = original)
            assertSame(original, next)
            assertSame(palette, next.palette())
        }
        assertNotSame(original, HostChromeColors.resolve(true, false, accent, previous = original))
        assertNotSame(original, HostChromeColors.resolve(false, false, 0xFFFFDD00.toInt(), previous = original))
        assertNotSame(original, HostChromeColors.resolve(false, false, accent, accent, original))
    }

}
