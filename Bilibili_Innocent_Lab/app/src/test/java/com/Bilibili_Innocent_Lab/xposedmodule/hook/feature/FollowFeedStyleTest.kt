package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceFadeCurve
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test

class FollowFeedStyleTest {
    @Test fun compactHeaderOnlyKeepsUnconsumedStatusBarInset() {
        assertEquals(90, FollowFeedStyle.contentTopPadding(90, 0))
        assertEquals(0, FollowFeedStyle.contentTopPadding(90, 90))
        assertEquals(0, FollowFeedStyle.contentTopPadding(0, 0))
        assertEquals(0, FollowFeedStyle.contentTopPadding(24, 40))
        assertEquals(48, FollowFeedStyle.contentTopPadding(72, 24))
        assertEquals(176, FollowFeedStyle.segmentWidth(1f))
        assertEquals(256, FollowFeedStyle.segmentWidth(2f))
    }
    @Test fun cardUsesAuditedStaticPresetWithOpaqueReadingSupport() {
        val card = FollowFeedStyle.card()
        assertEquals(SurfaceRole.CARD, card.role)
        assertEquals(LumenSurfaceMaterial.STATIC, card.material)
        assertFalse(card.sampling.enabled)
        assertEquals(1f, card.opacity, 0f)
        assertEquals(1f, card.fallbackTintOpacity, 0f)
        assertTrue(card.tintEnabled)
        assertEquals(16f, card.radiusDp, 0f)
        assertEquals(SurfaceRole.SELECTED_ITEM, FollowFeedStyle.selection().role)
        assertFalse(FollowFeedStyle.selection().sampling.enabled)
    }

    @Test fun statusFusionUsesPublicFadingBandWithinTheSharedBudget() {
        val palette = FollowFeedStyle.palette(HostChromeColors(false, 0xFF5566DD.toInt()))
        val band = FollowFeedStyle.statusBand(palette, 90, 123)
        assertEquals(SurfaceRole.TOP_BAR, band.role)
        assertEquals(0f, band.radiusDp, 0f)
        assertFalse(band.edgeEnabled)
        assertTrue(band.sampling.enabled)
        assertTrue(band.sampling.fadeEnabled)
        assertFalse(band.sampling.refractionEnabled)
        assertEquals(0f, band.sampling.fadeHold, 0f)
        assertEquals(LumenSurfaceFadeCurve.LINEAR, band.sampling.fadeCurve)
        assertEquals(112f / 123f, band.sampling.fadeEnd, .0001f)
        assertTrue(band.sampling.fadeEnd < 1f)
        val zeroInset = FollowFeedStyle.statusBand(palette, 0, 12)
        assertEquals(0f, zeroInset.sampling.fadeHold, 0f)
        assertEquals(8f / 12f, zeroInset.sampling.fadeEnd, .0001f)
        for ((inset, height) in listOf(0 to 0, 135 to 168, 24 to 12)) {
            val fade = FollowFeedStyle.statusBand(palette, inset, height).sampling
            assertTrue(fade.fadeHold in 0f..fade.fadeEnd && fade.fadeEnd <= 1f)
        }
        assertEquals(palette.background, band.color)
        assertEquals(1f, band.fallbackTintOpacity, 0f)
        assertEquals(16, FollowFeedStyle.MAX_CARD_SURFACES + 3)
    }

    @Test fun hostThemeFeedsSurfaceAndTextFromOnePalette() {
        for (dark in listOf(false, true)) {
            val palette = FollowFeedStyle.palette(HostChromeColors(dark, 0xFF5566DD.toInt()))
            assertEquals(0xFF5566DD.toInt(), palette.primary)
            assertEquals(255, palette.surface ushr 24)
            assertEquals(255, palette.background ushr 24)
            assertNotEquals(palette.textPrimary, palette.surface)
            assertNotEquals(palette.textSecondary, palette.surface)
            assertEquals(palette.surfaceVariant, FollowFeedStyle.track(palette).color)
        }
    }

    @Test fun allDynamicModuleCountsAndRecycledIdentitiesKeepTheirBoundaries() {
        // 视频、图文、转发的模块数量不同，统一以身份和首尾角色分组。
        for (count in listOf(3, 5, 9)) {
            val dynamic = (0 until count).map { FollowFeedRow(42, it == 0, it == count - 1, it + 7) }
            dynamic.zipWithNext().forEach { (a, b) -> assertTrue(FollowFeedGrouping.joins(a, b)) }
            assertFalse(FollowFeedGrouping.joins(dynamic.last(), FollowFeedRow(43, true, false, count + 7)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(43, false, false, 8)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(42, false, false, 9)))
            assertFalse(FollowFeedGrouping.joins(dynamic.first(), FollowFeedRow(42, true, false, 8)))
        }
        assertFalse(FollowFeedGrouping.joins(FollowFeedRow(-1, false, false, 0), FollowFeedRow(-1, false, false, 1)))
    }

    @Test fun dockPaddingUsesCoverageUnionAcrossWindowSizes() {
        assertEquals(176, FollowFeedStyle.bottomPadding(48, 2340, 2196, 32, 32))
        assertEquals(176, FollowFeedStyle.bottomPadding(176, 2340, 2196, 32, 32))
        assertEquals(80, FollowFeedStyle.bottomPadding(80, 2080, 2196, 32, 32))
        assertEquals(144, FollowFeedStyle.bottomPadding(0, 1080, 968, 24, 32))
        assertEquals(32, FollowFeedStyle.bottomPadding(12, 1080, null, 32, 32))
    }

    @Test fun prependingDoesNotConfuseStaleBindPositionsOrMembershipColors() {
        assertTrue(FollowFeedGrouping.joins(FollowFeedRow(42, true, false, 10),
            FollowFeedRow(42, false, true, 30), adjacent = true))
        assertFalse(FollowFeedGrouping.joins(FollowFeedRow(42, true, false, 10),
            FollowFeedRow(42, false, true, 11), adjacent = false))
        assertTrue(FollowFeedStyle.isNeutralText(0xFF212121.toInt()))
        assertFalse(FollowFeedStyle.isNeutralText(0xFFFF6699.toInt()))
        assertTrue(FollowFeedStyle.frequentHeight(2f) > FollowFeedStyle.frequentHeight(1f))
    }
}
