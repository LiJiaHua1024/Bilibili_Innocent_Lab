package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentSample
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
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
        for (role in listOf(SurfaceRole.FLOATING, SurfaceRole.SELECTED_ITEM)) {
            assertTrue(ModernMaterialPolicy.surface(role, true).upperEdgeAlpha <
                ModernMaterialPolicy.surface(role, false).upperEdgeAlpha)
        }
    }

    @Test fun foregroundOnHostAccentRemainsReadableForLightAndDarkSkins() {
        for (color in listOf(accent, 0xFF151515.toInt(), 0xFFFFDD00.toInt())) {
            val palette = HostChromeColors(false, color).palette()
            assertTrue(GlowLegibilityPolicy.contrast(
                GlowLegibilityPolicy.relativeLuminance(palette.primary),
                GlowLegibilityPolicy.relativeLuminance(palette.onPrimary)
            ) >= GlowLegibilityPolicy.TARGET_CONTRAST)
        }
    }

    @Test fun darkGlassThickensOverBrightContentAndReturnsToNeutralOverDarkContent() {
        val surface = HostChromeColors(true, accent).palette().surface
        val base = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, true).tintAlpha
        val bright = HostChromeLegibility.tintAlpha(GlowContentSample(1f, 0f), surface, base)
        assertTrue(bright > base)
        assertTrue(bright <= 235)
        assertEquals(base, HostChromeLegibility.tintAlpha(GlowContentSample(.04f, 0f), surface, base))
        assertEquals(base, HostChromeLegibility.tintAlpha(null, surface, base))
        val lightSurface = HostChromeColors(false, accent).palette().surface
        assertEquals(base, HostChromeLegibility.tintAlpha(GlowContentSample(1f, .1f), lightSurface, base))
    }
}
