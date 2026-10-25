package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeBackdropRecoveryTest {
    private class Page(var visible: Boolean = true, var attached: Boolean = true)
    private class Scene {
        val recovery = HostChromeBackdropRecovery<Page>()
        var page: Page? = null
        var probes = 0
        var now = 0L
        fun draw() = recovery.select(now, { it.visible && it.attached }) { probes++; page }
        fun nextProbe() {
            now += requireNotNull(recovery.retryAfter(now))
        }
    }

    @Test fun `home dynamic mine home restores the live source after the fallback window expires`() {
        val scene = Scene()
        val home = Page()
        scene.page = home
        assertSame(home, scene.draw())
        // Both intermediate pages are Compose-only and initially have no independent source.
        home.visible = false; scene.page = null; scene.now = 100
        assertNull(scene.draw())
        repeat(5) { scene.nextProbe(); assertNull(scene.draw()) }
        assertNull(scene.recovery.retryAfter(scene.now))
        val settled = scene.probes
        scene.now += 5000
        assertNull(scene.draw()); assertEquals(settled, scene.probes)
        // Returning to home must re-arm discovery, even though the fallback itself was valid.
        home.visible = true; scene.page = home
        scene.recovery.request(scene.now)
        assertSame(home, scene.draw())
        assertNull(scene.recovery.retryAfter(scene.now))
    }

    @Test fun `late page creation is discovered without requiring another navigation event`() {
        val scene = Scene()
        assertNull(scene.draw())
        scene.nextProbe(); assertNull(scene.draw())
        val page = Page(); scene.page = page
        scene.nextProbe()
        assertSame(page, scene.draw())
        assertEquals(3, scene.probes)
    }

    @Test fun `scrolling and repeated chrome recomposition reuse a healthy source without hierarchy scans`() {
        val scene = Scene().apply { page = Page() }
        val source = scene.draw()
        repeat(240) {
            scene.now += 16
            assertFalse(scene.recovery.request(scene.now))
            assertSame(source, scene.draw())
            assertNull(scene.recovery.retryAfter(scene.now))
        }
        assertEquals(1, scene.probes)
    }

    @Test fun `switching away from a detached or invisible source selects the new page`() {
        val scene = Scene().apply { page = Page() }
        val first = scene.draw()!!
        first.attached = false
        val second = Page(); scene.page = second
        assertSame(second, scene.draw())
        second.visible = false
        val third = Page(); scene.page = third
        assertSame(third, scene.draw())
        assertEquals(3, scene.probes)
    }

    @Test fun `invalid candidates never become a sticky source and requests are rate bounded`() {
        val scene = Scene().apply { page = Page(visible = false) }
        assertNull(scene.draw())
        repeat(63) {
            scene.now++
            scene.recovery.request(scene.now)
            assertNull(scene.draw())
        }
        assertEquals(1, scene.probes)
        scene.now = 64; scene.page!!.visible = true
        assertSame(scene.page, scene.draw())
        assertEquals(2, scene.probes)
    }

    @Test fun `detach clears the old source and permits a fresh attach immediately`() {
        val scene = Scene().apply { page = Page() }
        val old = scene.draw()
        scene.recovery.clear()
        val next = Page(); scene.page = next
        assertSame(next, scene.draw()); assertNotSame(old, next)
        assertEquals(2, scene.probes)
    }
}
