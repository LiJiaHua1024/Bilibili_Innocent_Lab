package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlin.math.abs

/** 在 DOWN 时固定滚动边界：一次分类滑动即使刚到尽头，也不会中途变成收岛。 */
internal class HostTopIslandGesture(
    private val startX: Float,
    private val width: Float,
    private val edgeWidth: Float,
    private val touchSlop: Float,
    private val collapseDistance: Float,
    private val startsOnAction: Boolean,
    private val canScrollLeft: Boolean,
    private val canScrollRight: Boolean
) {
    enum class Decision { PENDING, NATIVE, COLLAPSE }

    private var decision = Decision.PENDING

    fun move(dx: Float, dy: Float): Decision {
        if (decision != Decision.PENDING) return decision
        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return decision
        if (abs(dy) >= abs(dx)) return keepNative()

        val fromLeft = startX <= edgeWidth && dx > 0f
        val fromRight = startX >= width - edgeWidth && dx < 0f
        if (!fromLeft && !fromRight) return keepNative()
        // 手指右移对应内容向左滚；手指左移对应内容向右滚。
        val canScroll = if (dx > 0f) canScrollLeft else canScrollRight
        if (!startsOnAction && canScroll) return keepNative()
        if (abs(dx) >= collapseDistance && abs(dx) >= abs(dy) * 1.5f) {
            decision = Decision.COLLAPSE
        }
        return decision
    }

    fun keepNative(): Decision {
        decision = Decision.NATIVE
        return decision
    }
}
