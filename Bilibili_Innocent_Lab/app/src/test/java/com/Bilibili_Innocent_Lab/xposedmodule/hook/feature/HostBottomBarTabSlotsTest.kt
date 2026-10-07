package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class HostBottomBarTabSlotsTest {
    @Test fun hiddenMiddleTabsDoNotConsumeSlotsOrChangeHostIndices() {
        val slots = HostBottomBarTabSlots()
        assertTrue(slots.update(5) { it !in setOf(1, 3) })
        assertEquals(3, slots.count)
        assertEquals(listOf(0, 2, 4), (0 until slots.count).map(slots::hostIndex))
        assertEquals(2, slots.slotOf(4))
        assertEquals(-1, slots.slotOf(3))
        assertEquals(-1, slots.hostIndex(3))
        repeat(100) { assertFalse(slots.update(5) { it != 1 && it != 3 }) }
    }

    @Test fun rebindingCanHideRestoreAndRemoveTabsWithoutKeepingOldSlots() {
        val slots = HostBottomBarTabSlots()
        slots.update(5) { true }
        assertTrue(slots.update(5) { it != 0 && it != 2 })
        assertEquals(listOf(1, 3, 4), (0 until slots.count).map(slots::hostIndex))
        assertTrue(slots.update(5) { true })
        assertEquals(4, slots.slotOf(4))
        // 漫游提前剔除条目时，宿主自己的索引已经压缩。
        assertTrue(slots.update(3) { true })
        assertEquals(listOf(0, 1, 2), (0 until slots.count).map(slots::hostIndex))
        assertTrue(slots.update(3) { false })
        assertEquals(0, slots.count)
        assertEquals(-1, slots.hostIndex(0))
        assertEquals(-1, slots.slotOf(0))
    }
}
