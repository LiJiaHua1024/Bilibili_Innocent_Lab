package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Captured native caller/ABI evidence, including exact-build identity and older native layouts. */
class HostChromeTopScopeMatrixTest {
    @Test fun `all audited APK row roles stay distinct and share their native model`() {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/hostui/top-row-scope-37.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val samples = fixture.getJSONArray("samples")
        assertEquals(37, samples.length())
        val versions = HashSet<Int>()
        var rows = 0
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            assertTrue(versions.add(sample.getInt("version_code")))
            assertTrue(sample.getString("apk_sha256").matches(Regex("[0-9a-f]{64}")))
            val root = sample.getJSONArray("root")
            val foreground = sample.getJSONArray("foreground")
            val actions = sample.getJSONArray("actions")
            if (!sample.getBoolean("compose_row_present")) {
                assertEquals(0, root.length()); assertEquals(0, foreground.length()); assertEquals(0, actions.length())
                continue
            }
            rows++
            assertEquals(1, root.length()); assertEquals(1, foreground.length()); assertEquals(1, actions.length())
            val r = root.getJSONObject(0); val f = foreground.getJSONObject(0); val a = actions.getJSONObject(0)
            fun params(method: JSONObject) = strings(method.getJSONArray("parameters"))
            val owner = r.getString("owner")
            for (method in listOf(r, f, a)) {
                assertEquals(owner, method.getString("owner"))
                assertEquals("void", method.getString("result"))
                assertEquals(params(r).first(), params(method).first())
            }
            assertTrue(HomeChromeComposeLocator.accepts("top_backdrop", owner, params(r)))
            assertTrue(HomeChromeComposeLocator.accepts("top_foreground", owner, params(f)))
            assertTrue(HomeChromeComposeLocator.accepts("top_right_actions", owner, params(a)))
            assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner, params(r)))
            assertFalse(HomeChromeComposeLocator.accepts("top_backdrop", owner, params(f)))
            assertFalse(HomeChromeComposeLocator.accepts("top_right_actions", owner, params(f)))
            assertFalse(HomeChromeComposeLocator.accepts("top_right_actions", owner, params(r)))
            assertFalse(HomeChromeComposeLocator.accepts("top_foreground", owner, params(a)))
        }
        assertEquals(30, rows)
    }

    private fun strings(values: JSONArray) = (0 until values.length()).map(values::getString)
}
