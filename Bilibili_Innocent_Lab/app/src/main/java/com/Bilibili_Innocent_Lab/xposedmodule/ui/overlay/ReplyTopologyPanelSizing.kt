package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import kotlin.math.roundToInt

internal object ReplyTopologyPanelSizing {
    fun dimension(available: Int, density: Float, fraction: Float, minDp: Int, maxDp: Int): Int {
        val limit = available.coerceAtLeast(1)
        val minimum = (minDp * density).roundToInt().coerceIn(1, limit)
        val maximum = (maxDp * density).roundToInt().coerceIn(minimum, limit)
        return (limit * fraction).roundToInt().coerceIn(minimum, maximum)
    }
}
