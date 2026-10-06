package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlin.math.abs

/** 收起态的点击与水平拉开互斥；滑开再滑回、多指和取消都不能误触点击。 */
internal class HostTopIslandCompactGesture(private val slop: Float, private val expandDistance: Float) {
    enum class Decision { PENDING, EXPAND, CANCEL }
    private var decision = Decision.PENDING
    private var dragged = false

    fun move(dx: Float, dy: Float): Decision {
        if (decision != Decision.PENDING) return decision
        if (abs(dx) > slop || abs(dy) > slop) dragged = true
        if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) return cancel()
        if (abs(dx) >= expandDistance && abs(dx) >= abs(dy) * 1.1f) decision = Decision.EXPAND
        return decision
    }

    fun cancel(): Decision {
        dragged = true
        decision = Decision.CANCEL
        return decision
    }

    fun canClick() = decision == Decision.PENDING && !dragged
}
