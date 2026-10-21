package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchProcessFeaturePolicyTest {
    @Test fun `only the observed embedded web process is isolated`() {
        assertFalse(NPatchProcessFeaturePolicy.allowsFeatureHooks(NPatchMode.EMBEDDED, "tv.danmaku.bili:web"))
        for (mode in listOf(NPatchMode.MANAGER, NPatchMode.UNKNOWN)) {
            assertTrue(NPatchProcessFeaturePolicy.allowsFeatureHooks(mode, "tv.danmaku.bili:web"))
        }
        for (process in listOf("tv.danmaku.bili", "tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice", "tv.danmaku.bili:web2")) {
            assertTrue(NPatchProcessFeaturePolicy.allowsFeatureHooks(NPatchMode.EMBEDDED, process))
        }
    }
}
