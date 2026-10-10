package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundWorld
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundWorldTest {
    @Test fun galaxyCourseIsContinuousAndDoesNotUseTheOldSinePeriod() {
        val oldPeriod = 20 * Math.PI
        for (y in listOf(-50.0, 0.0, 50.0, 12_500_000.0)) {
            assertEquals(HostBackgroundWorld.galaxyCenter(y - .000001), HostBackgroundWorld.galaxyCenter(y + .000001), .0001)
            assertNotEquals(HostBackgroundWorld.galaxyCenter(y), HostBackgroundWorld.galaxyCenter(y + oldPeriod), .0001)
        }
    }
    @Test fun cloudFieldIsContinuousAtPositiveNegativeAndDistantLatticeBoundaries() {
        for (y in listOf(-100.0, 0.0, 100.0, 12_500_000.0)) {
            val before = HostBackgroundWorld.fractal(.37, y - .000001)
            val after = HostBackgroundWorld.fractal(.37, y + .000001)
            assertEquals(before, after, .0001)
            assertTrue(before in 0.0..1.0)
        }
    }

    @Test fun bandsHaveStableDistinctSeedsBeyondIntegerScrollRange() {
        val bands = listOf(-1L, 0L, 1L, 3_000_000_000L, 3_000_000_001L)
        val seeds = bands.map { HostBackgroundWorld.seed(it, HostBackgroundPreset.STARRY) }
        assertEquals(bands.size, seeds.toSet().size)
        assertEquals(seeds, bands.map { HostBackgroundWorld.seed(it, HostBackgroundPreset.STARRY) })
        assertNotEquals(seeds[1], HostBackgroundWorld.seed(0, HostBackgroundPreset.NEBULA))
    }
}
