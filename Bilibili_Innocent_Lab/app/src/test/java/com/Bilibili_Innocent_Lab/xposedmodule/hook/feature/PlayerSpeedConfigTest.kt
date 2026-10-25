package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedConfigTest {
    @Test fun `range is bounded with follow host sentinel`() {
        assertNull(PlayerSpeedConfig.multiplier(0))
        assertEquals(0.1f, PlayerSpeedConfig.multiplier(10))
        assertEquals(0.25f, PlayerSpeedConfig.multiplier(25))
        assertEquals(4f, PlayerSpeedConfig.multiplier(400))
        assertEquals(8f, PlayerSpeedConfig.multiplier(800))
        listOf(-1, 1, 9, 801, Int.MAX_VALUE).forEach {
            assertEquals(0, PlayerSpeedConfig.normalize(it))
            assertNull(PlayerSpeedConfig.multiplier(it))
        }
    }
    @Test fun `decimal parsing never rounds invalid input into range`() {
        listOf("NaN", "Infinity", "", " ", "1.001", "0.099", "0.09", "8.001", "8.01", "-1", "1,25").forEach {
            assertNull(it, PlayerSpeedConfig.parseMultiplier(it))
        }
        assertEquals(125, PlayerSpeedConfig.parseMultiplier(" 1.25 "))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier("0.1"))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier(".10"))
        assertEquals(25, PlayerSpeedConfig.parseMultiplier("0.25"))
        assertEquals(400, PlayerSpeedConfig.parseMultiplier("4.00"))
        assertEquals(475, PlayerSpeedConfig.parseMultiplier("4.75"))
        assertEquals(800, PlayerSpeedConfig.parseMultiplier("8"))
        assertEquals("0.1", PlayerSpeedConfig.formatMultiplier(10))
        assertEquals("8", PlayerSpeedConfig.formatMultiplier(800))
    }
    @Test fun `all supported values round trip without floating point drift`() {
        PlayerSpeedConfig.supportedPercents.forEach {
            assertEquals(it, PlayerSpeedConfig.parseMultiplier(PlayerSpeedConfig.formatMultiplier(it)))
        }
    }
    @Test fun `disable wins without changing saved custom speed`() {
        assertEquals(0, PlayerSpeedConfig.effectiveLongPressPercent(true, 275))
        assertEquals(275, PlayerSpeedConfig.effectiveLongPressPercent(false, 275))
    }
    @Test fun `menu multipliers parse valid 0_1 to 8 values and sort descending with deduplication`() {
        assertEquals(
            listOf(800, 300, 200, 150, 125, 100, 75, 50, 10),
            PlayerSpeedConfig.parseMenuMultipliers("0.5, 0.75, 1.0, 1.25x, 1.5X，2.0；3倍 8 0.10 1.5")
        )
        listOf("", "   ", "0", "0, 1.0", "0.09, 1.0", "1.0, 8.01", "1.0, 1.234", "1.0, abc").forEach {
            assertNull(it, PlayerSpeedConfig.parseMenuMultipliers(it))
        }
        val tooMany = (1..13).joinToString(", ") { "1.$it" }
        assertNull(PlayerSpeedConfig.parseMenuMultipliers(tooMany))
    }
    @Test fun `stored menu spec round trips and rejects malformed or out of range strings`() {
        val percents = listOf(300, 200, 150, 125, 100, 75, 50, 10)
        val encoded = PlayerSpeedConfig.encodeMenuPercents(percents)
        assertEquals("300,200,150,125,100,75,50,10", encoded)
        assertEquals(percents, PlayerSpeedConfig.parseStoredMenuPercents(encoded))
        assertEquals("3, 2, 1.5, 1.25, 1, 0.75, 0.5, 0.1", PlayerSpeedConfig.formatMenuInput(percents))
        assertEquals("3x / 2x / 1.5x / 1.25x / 1x / 0.75x / 0.5x / 0.1x", PlayerSpeedConfig.formatMenuSummary(percents))
        listOf(null, "", "  ", "0", "9,100", "801,100", "100,bad", "1".repeat(200),
            "1,100", "1.25,100", "100,,50", ",100", "100,", "+100").forEach {
            assertTrue(PlayerSpeedConfig.parseStoredMenuPercents(it).isEmpty())
        }
    }
    @Test fun `every supported menu speed round trips with the same precision as single speed`() {
        for (percent in PlayerSpeedConfig.MIN_PERCENT..PlayerSpeedConfig.MAX_PERCENT) {
            assertEquals(listOf(percent), PlayerSpeedConfig.parseMenuMultipliers(PlayerSpeedConfig.formatMultiplier(percent)))
            assertEquals(listOf(percent), PlayerSpeedConfig.parseStoredMenuPercents(PlayerSpeedConfig.encodeMenuPercents(listOf(percent))))
        }
        val twelve = (100..111).toList()
        assertEquals(twelve.reversed(), PlayerSpeedConfig.parseMenuMultipliers(PlayerSpeedConfig.formatMenuInput(twelve)))
        assertNull(PlayerSpeedConfig.parseMenuMultipliers("1,".repeat(100)))
        assertTrue(PlayerSpeedConfig.parseStoredMenuPercents((100..112).joinToString(",")).isEmpty())
    }
    @Test fun `menu customization is mutually exclusive with default speed but independent of long press`() {
        val menuSpec = "300,200,150,100,50"
        assertEquals(listOf(300, 200, 150, 100, 50), PlayerSpeedConfig.effectiveMenuPercents(0, menuSpec))
        assertEquals(listOf(3f, 2f, 1.5f, 1f, 0.5f), PlayerSpeedConfig.menuMultipliers(0, menuSpec))
        assertFalse(PlayerSpeedConfig.conflictsWithDefaultSpeed(0, menuSpec))

        // Default speed active -> menu customization suppressed
        assertTrue(PlayerSpeedConfig.conflictsWithDefaultSpeed(150, menuSpec))
        assertTrue(PlayerSpeedConfig.effectiveMenuPercents(150, menuSpec).isEmpty())
        assertTrue(PlayerSpeedConfig.menuMultipliers(150, menuSpec).isEmpty())

        // Long-press speed remains independent when menu customization is active
        assertEquals(300, PlayerSpeedConfig.effectiveLongPressPercent(false, 300))
    }
}
