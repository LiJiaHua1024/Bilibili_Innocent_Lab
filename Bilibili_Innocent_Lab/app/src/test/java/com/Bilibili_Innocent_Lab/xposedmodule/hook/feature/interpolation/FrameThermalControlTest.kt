package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameThermalControlTest {
    @Test fun `disabled temperature control never queries a sensor or pauses`() {
        var queries = 0
        assertNull(FrameThermalControl.pauseHeadroom(false) { queries++; 1f })
        assertEquals(0, queries)
    }

    @Test fun `enabled temperature control preserves the original boundary`() {
        assertNull(FrameThermalControl.pauseHeadroom(true) { .849f })
        assertEquals(.85f, FrameThermalControl.pauseHeadroom(true) { .85f }!!, 0f)
        assertEquals(1f, FrameThermalControl.pauseHeadroom(true) { 1f }!!, 0f)
    }

    @Test fun `missing and invalid temperature evidence keep the original no-pause behavior`() {
        assertNull(FrameThermalControl.pauseHeadroom(true) { null })
        assertNull(FrameThermalControl.pauseHeadroom(true) { Float.NaN })
    }
}
