package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 宿主底栏拖动滑块的落点：落回当前页不算操作（宿主点击当前 tab 会刷新内容）。 */
class HostBottomBarScrubReleaseTest {
    @Test fun scrubLandedBackOnTheCurrentPageIsNotAnOperation() {
        assertNull(HostBottomBarScrubRelease.selectableTarget(0, scrubbed = true, currentPage = 0))
        assertNull(HostBottomBarScrubRelease.selectableTarget(2, scrubbed = true, currentPage = 2))
    }

    @Test fun scrubLandedOnAnotherPageStillSelectsThatPage() {
        assertEquals(2, HostBottomBarScrubRelease.selectableTarget(2, scrubbed = true, currentPage = 0))
        assertEquals(0, HostBottomBarScrubRelease.selectableTarget(0, scrubbed = true, currentPage = 3))
    }

    @Test fun tapAndInertReleasesKeepTheirOriginalSemantics() {
        // 普通点击（非拖动）：点当前 tab 仍是宿主的刷新行为，照旧交回宿主。
        assertEquals(1, HostBottomBarScrubRelease.selectableTarget(1, scrubbed = false, currentPage = 1))
        assertEquals(0, HostBottomBarScrubRelease.selectableTarget(0, scrubbed = false, currentPage = 0))
        // 纵向弹性回弹、拖动取消与未完成手势：本来就带不回落点页。
        assertNull(HostBottomBarScrubRelease.selectableTarget(null, scrubbed = false, currentPage = 1))
        assertNull(HostBottomBarScrubRelease.selectableTarget(null, scrubbed = true, currentPage = 1))
    }
}
