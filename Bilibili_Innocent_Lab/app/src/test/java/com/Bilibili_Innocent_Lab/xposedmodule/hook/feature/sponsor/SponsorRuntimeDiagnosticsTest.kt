package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorRuntimeDiagnosticsTest {
    @Test fun repeatedProgressAndFailuresWriteOnlyOncePerEvent() {
        val logs = mutableListOf<Triple<String, String, Boolean>>()
        val diagnostics = SponsorRuntimeDiagnostics { key, text, error -> logs += Triple(key, text, error) }
        repeat(1000) { diagnostics.record("progress.observed"); diagnostics.record("query.failed.timeout") }
        assertEquals(2, logs.size)
        assertFalse(logs.first().third); assertTrue(logs.last().third)
        assertNotEquals(logs.first().first, logs.last().first)
    }

    @Test fun eventCountAndMessageLengthHaveHardBudgets() {
        val logs = mutableListOf<String>()
        val diagnostics = SponsorRuntimeDiagnostics { _, text, _ -> logs += text }
        repeat(100) { diagnostics.record("event$it", "x".repeat(200)) }
        assertEquals(32, logs.size); assertTrue(logs.all { it.length < 210 })
    }
}
