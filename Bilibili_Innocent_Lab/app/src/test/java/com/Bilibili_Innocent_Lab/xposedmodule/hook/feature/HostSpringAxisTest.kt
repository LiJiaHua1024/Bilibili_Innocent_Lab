package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSpringAxis
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticSpringAxis
import org.junit.Assert.*
import org.junit.Test

class HostSpringAxisTest {
    @Test fun retargetingKeepsPositionAndVelocityContinuousAndEventuallySettles() {
        val axis = HostSpringAxis()
        repeat(8) { axis.advance(1f / 60f, 1f) }
        val position = axis.value
        val velocity = axis.velocity
        axis.advance(0f, 0f)
        assertEquals(position, axis.value, .00001f)
        assertEquals(velocity, axis.velocity, .00001f)
        repeat(180) { axis.advance(1f / 60f, 0f) }
        assertTrue(axis.atRest(0f, .001f))
    }

    @Test fun configurableStiffnessAndResetKeepTheAdapterReusable() {
        val normal = HostSpringAxis()
        val slow = HostSpringAxis()
        repeat(10) { normal.advance(1f / 60f, 1f); slow.advance(1f / 60f, 1f, 115f, .84f) }
        assertTrue(slow.value < normal.value)
        slow.reset(.4f)
        assertEquals(.4f, slow.value, 0f)
        assertEquals(0f, slow.velocity, 0f)
        repeat(180) { slow.advance(1f / 60f, 1f) }
        assertTrue(slow.atRest(1f, .001f))
    }

    @Test fun migratedTrajectoriesMatchTheOriginalIncludingReversalsAndRestThresholds() {
        for (hz in listOf(60, 90, 120)) {
            val original = ElasticSpringAxis()
            val migrated = HostSpringAxis()
            for ((target, stiffness, damping) in listOf(
                Triple(1f, 115f, .84f), Triple(0f, 88f, .68f),
                Triple(1f, 420f, .8f), Triple(0f, 420f, .8f))) {
                repeat(hz / 3) {
                    original.advance(1f / hz, target, stiffness, damping)
                    migrated.advance(1f / hz, target, stiffness, damping)
                    assertEquals("position at $hz Hz", original.value, migrated.value, .00001f)
                    assertEquals("velocity at $hz Hz", original.velocity, migrated.velocity, .0001f)
                    assertEquals(original.atRest(target, .5f / 871f), migrated.atRest(target, .5f / 871f))
                }
            }
        }
    }
}
