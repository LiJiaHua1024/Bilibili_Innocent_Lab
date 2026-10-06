package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlin.math.abs

/** 实际滑动过的分类永不转为收岛；边缘未带动分类的内滑可以收岛。 */
internal class HostTopIslandGesture(
    private val startX: Float,
    private val width: Float,
    private val edgeWidth: Float,
    private val touchSlop: Float,
    private val collapseDistance: Float,
    private val startsOnAction: Boolean,
    private val canScrollLeft: Boolean,
    private val canScrollRight: Boolean,
    private val ignoredScrollDistance: Float = collapseDistance
) {
    enum class Decision { PENDING, NATIVE, COLLAPSE }

    private var decision = Decision.PENDING
    private var nativeProbeStarted = false

    fun move(dx: Float, dy: Float, nativeMoved: Boolean = false, finishing: Boolean = false): Decision {
        if (decision != Decision.PENDING) return decision
        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return decision
        if (startX > edgeWidth && startX < width - edgeWidth) return keepNative()
        if (abs(dy) >= maxOf(touchSlop * 2f, collapseDistance) && abs(dy) > abs(dx) * 1.4f) return keepNative()

        val fromLeft = startX <= edgeWidth && dx > 0f
        val fromRight = startX >= width - edgeWidth && dx < 0f
        if (!fromLeft && !fromRight) {
            // 很短的反向起手不固定整次手势；明确向外的拖动仍交给宿主。
            if (abs(dx) >= collapseDistance) return keepNative()
            return decision
        }
        // 手指右移对应内容向左滚；手指左移对应内容向右滚。
        val canScroll = if (dx > 0f) canScrollLeft else canScrollRight
        if (nativeMoved && canScroll && !startsOnAction) return keepNative()
        // 原生滚动控件首个 MOVE 可能只接管拖动，没有位移；至少再观察一次。
        if (!finishing && !startsOnAction && canScroll && !nativeProbeStarted) {
            nativeProbeStarted = true
            return decision
        }
        val distance = if (!startsOnAction && canScroll) ignoredScrollDistance else collapseDistance
        if (abs(dx) >= distance && abs(dx) >= abs(dy) * .9f) {
            decision = Decision.COLLAPSE
        }
        return decision
    }

    fun keepNative(): Decision {
        decision = Decision.NATIVE
        return decision
    }
}
