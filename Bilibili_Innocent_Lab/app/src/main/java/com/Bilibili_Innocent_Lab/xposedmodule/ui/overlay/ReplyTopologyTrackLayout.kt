package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import kotlin.math.floor
import kotlin.math.min

/** 行留白、轨道和圆环共用的宽度预算；先保留正文，窄窗口减少可见层数。 */
internal data class ReplyTopologyTrackLayout(
    val width: Float,
    val trackWidth: Float,
    val startX: Float,
    val laneSpacing: Float,
    val maxLane: Int,
    val nodeRadius: Float,
    val ringRadius: Float,
    val strokeWidth: Float
) {
    fun laneX(depth: Int): Float = startX + depth.coerceIn(0, maxLane) * laneSpacing
    fun compresses(depth: Int): Boolean = depth > maxLane

    companion object {
        fun resolve(width: Float, density: Float, fontScale: Float): ReplyTopologyTrackLayout {
            val w = width.takeIf { it.isFinite() && it > 0f } ?: 0f
            val d = density.takeIf { it.isFinite() && it > 0f } ?: 1f
            val f = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val available = (w - min(12f * d, w * 0.1f)).coerceAtLeast(0f)
            val text = min(160f * d * f, available * 0.75f)
            val rail = min(88f * d, (available - text).coerceAtLeast(0f))
            val gap = min(6f * d, rail * 0.1f)
            val stroke = min(1.5f * d, rail * 0.1f)
            val outer = min(7.75f * d, ((rail - gap) * 0.5f).coerceAtLeast(0f))
            val start = min(14f * d, (rail - gap - outer).coerceAtLeast(outer))
            val spacing = 8f * d
            val lanes = floor(((rail - gap - outer - start) / spacing).coerceAtLeast(0f))
                .toInt().coerceIn(0, 7)
            return ReplyTopologyTrackLayout(w, rail, start, spacing, lanes,
                min(3.5f * d, ((outer - stroke * 0.5f) / 1.2f).coerceAtLeast(0f)),
                (outer - stroke * 0.5f).coerceAtLeast(0f), stroke)
        }
    }
}
