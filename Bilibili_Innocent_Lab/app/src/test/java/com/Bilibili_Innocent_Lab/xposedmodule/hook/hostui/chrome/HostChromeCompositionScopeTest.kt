package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeCompositionScopeTest {
    @Test fun `top layout restart shares one root claim and still works without its outer caller`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        val layout = scope.enter("top")
        assertEquals("top", scope.claimTogether { it == "top" })
        scope.exit(layout)
        assertNull(scope.current()); assertNull(scope.claim())
        scope.exit(outer)
        val restart = scope.enter("top")
        assertEquals("top", scope.claimTogether { it == "top" })
        scope.exit(restart)
        assertNull(scope.current())
    }
    @Test fun `only first root is claimed and outside composition stays untouched`() {
        val scope = HostChromeCompositionScope<String>()
        assertNull(scope.current()); assertNull(scope.claim())
        val outer = scope.enter("top")
        assertEquals("top", scope.current()); assertEquals("top", scope.claim())
        assertNull(scope.current()); assertNull(scope.claim())
        scope.exit(outer); assertNull(scope.claim())
    }
    @Test fun `nested containers have independent roots and restore outer scope`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        val inner = scope.enter("bottom")
        assertEquals("bottom", scope.claim()); scope.exit(inner)
        assertEquals("top", scope.claim()); scope.exit(outer)
        assertNull(scope.current())
    }
    @Test fun `native failures still release exact invocation`() {
        val scope = HostChromeCompositionScope<String>()
        val outer = scope.enter("top")
        runCatching {
            val inner = scope.enter("bottom")
            try { throw IllegalStateException("native") } finally { scope.exit(inner) }
        }
        assertEquals("top", scope.current())
        scope.exit(outer); assertNull(scope.claim())
    }
    @Test fun `stale exit cannot clear another invocation`() {
        val scope = HostChromeCompositionScope<String>()
        val stale = scope.enter("top"); scope.exit(stale)
        val current = scope.enter("top")
        scope.exit(stale); assertEquals("top", scope.claim())
        scope.exit(current)
    }
}
