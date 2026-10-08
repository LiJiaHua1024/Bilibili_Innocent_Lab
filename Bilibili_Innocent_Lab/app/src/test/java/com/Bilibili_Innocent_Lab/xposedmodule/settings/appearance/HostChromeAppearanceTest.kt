package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import org.junit.Assert.assertEquals
import org.junit.Test

class HostChromeAppearanceTest {
    @Test fun existingUsersAndOldBackupsKeepTheirSeparateBackends() {
        assertEquals(HostChromeAppearance(), HostChromeAppearance.read(null, null, bottom = false, enabled = true))
        assertEquals(HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE),
            HostChromeAppearance.read("soft", "legacy", bottom = true, enabled = true))
    }

    @Test fun firstEnableUsesAutomaticButExplicitChoicesSurviveDisabling() {
        assertEquals(HostGlassRenderer.AUTO, HostChromeAppearance.read(null, null, bottom = true, enabled = false).renderer)
        assertEquals(HostGlassRenderer.SOFTWARE, HostChromeAppearance.read("soft", "software", bottom = false, enabled = false).renderer)
        assertEquals(HostGlassRenderer.AUTO, HostChromeAppearance.read("soft", "auto", bottom = true, enabled = true).renderer)
    }

    @Test fun liquidTemporarilyUsesGpuWithoutErasingThePreviousSoftwareChoice() {
        val original = HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE)
        val liquid = original.copy(material = HostGlassMaterial.LIQUID)
        assertEquals(HostGlassRenderer.AUTO, liquid.effectiveRenderer)
        assertEquals(original, liquid.copy(material = HostGlassMaterial.SOFT))
        assertEquals(original, liquid.compatible(32))
        assertEquals(liquid, liquid.compatible(33))
    }

    @Test fun barsShareOriginalBlurStrengthWithASeparateSoftwareFallbackRadius() {
        val soft = HostSurfaceStyle.floating(false, 22f)
        assertEquals(LumenSurfaceMaterial.FROSTED, soft.material)
        assertEquals(17f, soft.sampling.blurRadiusDp, 0f)
        assertEquals(10f, soft.sampling.softwareBlurRadiusDp!!, 0f)
        val liquid = HostSurfaceStyle.floating(true, 32f, HostChromeAppearance(HostGlassMaterial.LIQUID, HostGlassRenderer.SOFTWARE))
        assertEquals(LumenSurfaceMaterial.LIQUID, liquid.material)
        assertEquals(LumenSurfaceBackend.AUTO, liquid.sampling.backend)
        assertEquals(soft.sampling.blurRadiusDp, liquid.sampling.blurRadiusDp, 0f)
        val software = HostSurfaceStyle.floating(false, 32f, HostChromeAppearance(renderer = HostGlassRenderer.SOFTWARE))
        assertEquals(LumenSurfaceBackend.SOFTWARE, software.sampling.backend)
    }
}
