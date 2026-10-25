package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeTopCapsuleTest {
    @Test fun `shadow and capsule stay inside the host row across densities`() {
        for (density in listOf(1f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) for (heightDp in listOf(32f, 36f, 40f, 48f)) {
            val width = 411f * density; val height = heightDp * density
            val capsule = requireNotNull(HostChromeTopCapsule.fit(width, height, density))
            val extent = HostChromeTopCapsule.shadowExtent(capsule.shadowRadius)
            assertTrue("vertical $density/$heightDp: $capsule", extent <= capsule.insetY)
            assertTrue("horizontal $density/$heightDp: $capsule", extent <= capsule.insetX)
            // The indicator sits a few dp above the row bottom; the inset never reaches it.
            assertTrue(capsule.insetY <= 2f * density + 1e-3f)
            assertEquals((height - 2f * capsule.insetY) / 2f, capsule.cornerRadius(height), 1e-3f)
        }
    }

    @Test fun `typical phone row keeps a visible contact shadow`() {
        val capsule = requireNotNull(HostChromeTopCapsule.fit(1080f, 40f * 2.75f, 2.75f))
        assertTrue(capsule.shadowRadius > 0f)
    }

    @Test fun `degenerate rows keep the full row and no shadow`() {
        assertNull(HostChromeTopCapsule.fit(0f, 100f, 3f))
        assertNull(HostChromeTopCapsule.fit(100f, Float.NaN, 3f))
        val tiny = requireNotNull(HostChromeTopCapsule.fit(200f, 10f, 3f))
        assertEquals(0f, tiny.shadowRadius, 0f)
        assertEquals(0f, HostChromeTopCapsule.shadowExtent(0f), 0f)
    }
}
