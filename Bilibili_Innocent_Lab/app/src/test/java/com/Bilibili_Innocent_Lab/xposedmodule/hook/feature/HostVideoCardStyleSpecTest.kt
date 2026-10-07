package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostVideoCardStyleSpecTest {
    @Test fun customRadiusUsesDensityAndFitsSmallCovers() {
        assertEquals(18f, HostVideoCardStyleSpec.coverRadius(200, 100), 0.001f)
        assertEquals(60f, HostVideoCardStyleSpec.cardRadius(200, 300, -1, 3f), 0f)
        assertEquals(0f, HostVideoCardStyleSpec.coverRadius(200, 100, 0, 3f), 0f)
        assertEquals(24f, HostVideoCardStyleSpec.coverRadius(200, 100, 8, 3f), 0f)
        assertEquals(50f, HostVideoCardStyleSpec.coverRadius(200, 100, 40, 3f), 0f)
        assertEquals(24f, HostVideoCardStyleSpec.cardRadius(200, 300, 8, 3f), 0f)
        assertEquals(-1, HostVideoCardStyleSpec.normalizeRadius(-2))
        assertEquals(-1, HostVideoCardStyleSpec.normalizeRadius(41))
    }

    @Test fun recycledCardCanSwitchColumnsWithoutAccumulatingSpacing() {
        val spacing = HostVideoCardSpacing(3, 4, 5, 6, 2.75f)
        repeat(100) {
            assertEquals(41, spacing.left(0))
            assertEquals(24, spacing.right(0))
            assertEquals(22, spacing.left(1))
            assertEquals(43, spacing.right(1))
            assertEquals(17, spacing.top(1))
            assertEquals(39, spacing.bottom(1))
        }
        // 换成全宽/单列卡片后，宿主原始边距必须完整恢复。
        assertEquals(3, spacing.left(-1))
        assertEquals(4, spacing.top(-1))
        assertEquals(5, spacing.right(-1))
        assertEquals(6, spacing.bottom(-1))
    }

    @Test fun scrollingAndRebindingSameSizeDoNotRebuildGeometry() {
        val geometry = HostVideoCardGeometry()
        assertTrue(geometry.update(462, 576, 76.12f))
        repeat(100) { assertFalse(geometry.update(462, 576, 76.12f)) }
        // 标题换行只改变高度，封面重测只改变顶部半径，二者都不能漏更新。
        assertTrue(geometry.update(462, 582, 76.12f))
        assertTrue(geometry.update(462, 582, 77f))
        assertFalse(geometry.update(462, 582, 77f))
    }
}
