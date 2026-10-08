package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.*
import org.junit.Test

class AgentPermissionWriteTest {
    @Test fun failedEnableRestoresMemoryEvenWhenDiskRemainsUnavailable() {
        var memory = false
        val writes = mutableListOf<Boolean>()
        val saved = AgentPreferences.writeEnabled(false, true) { value ->
            memory = value // Android preferences publish to memory before the disk result.
            writes += value
            false
        }
        assertFalse(saved)
        assertFalse("a subsequent task must still read disabled", memory)
        assertEquals(listOf(true, false), writes)
    }
    @Test fun failedDisableReportsFailureAndRestoresThePreviouslyEnabledValue() {
        var memory = true
        assertFalse(AgentPreferences.writeEnabled(true, false) { value -> memory = value; false })
        assertTrue(memory)
    }
    @Test fun successfulCommitDoesNotPerformASecondWrite() {
        val writes = mutableListOf<Boolean>()
        assertTrue(AgentPreferences.writeEnabled(false, true) { writes += it; true })
        assertEquals(listOf(true), writes)
    }
}
