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

    @Test fun publishReleaseActivatesOnlyTheActionAndKeepsTheOriginalPage() {
        for (originalPage in listOf(0, 4)) {
            val activated = mutableListOf<Int>()
            val selected = HostBottomBarScrubRelease.activateTarget(2, true, originalPage) {
                activated += it
                false // 宿主发布面板打开，但页面没有切换。
            }
            assertEquals(listOf(2), activated)
            assertEquals(originalPage, selected)
            // 回到原页面后的拖动松手不会再次触发首页刷新或“我的”点击。
            HostBottomBarScrubRelease.activateTarget(selected, true, selected) {
                activated += it
                true
            }
            assertEquals(listOf(2), activated)
        }
    }

    @Test fun pageSelectionRequiresTheHostToHandleTheClick() {
        assertEquals(3, HostBottomBarScrubRelease.activateTarget(3, true, 0) { true })
        assertEquals(0, HostBottomBarScrubRelease.activateTarget(3, true, 0) { false })
    }
}
