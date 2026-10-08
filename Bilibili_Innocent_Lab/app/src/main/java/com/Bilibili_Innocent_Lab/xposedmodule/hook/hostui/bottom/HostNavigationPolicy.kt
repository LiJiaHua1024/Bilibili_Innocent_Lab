package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounded geometry for a floating navigation surface.
 *
 * 保留宿主标签与隐藏项的几何契约；只处理落点、阻力和手势意图。
 * [MAX_ITEMS] 同时约束槽位映射与控制器；弹簧求值由引擎负责。
 */
internal object HostNavigationMotion {
    const val MAX_ITEMS = 8
    const val BAR_HEIGHT_DP = 64
    const val INSET_DP = 4
    const val MAX_TRAVEL_DP = 4f
    const val SPRING_DURATION_MS = 460L

    fun position(value: Float, count: Int): Float =
        if (value.isFinite()) value.coerceIn(0f, (count.coerceIn(1, MAX_ITEMS) - 1).toFloat()) else 0f

    fun nearestPage(value: Float, count: Int): Int = position(value, count).roundToInt()

    fun physicalSlot(value: Float, count: Int, rtl: Boolean): Float =
        if (rtl) count.coerceIn(1, MAX_ITEMS) - 1 - position(value, count) else position(value, count)

    fun indexAt(x: Float, contentWidth: Float, inset: Float, count: Int, rtl: Boolean): Int {
        if (count !in 1..MAX_ITEMS || contentWidth <= 0f || !contentWidth.isFinite() || !inset.isFinite() || !x.isFinite()) return -1
        val physical = ((x - inset) / (contentWidth / count)).toInt().coerceIn(0, count - 1)
        return if (rtl) count - 1 - physical else physical
    }

    fun scrubPosition(start: Float, distanceX: Float, slotWidth: Float, count: Int, rtl: Boolean): Float {
        if (slotWidth <= 0f || !distanceX.isFinite()) return position(start, count)
        return position(start + distanceX / slotWidth * if (rtl) -1f else 1f, count)
    }

    /** The vector magnitude approaches the limit; diagonal motion never gets a larger budget. */
    fun displacementScale(dx: Float, dy: Float, limit: Float, resistance: Float): Float {
        if (!dx.isFinite() || !dy.isFinite() || limit <= 0f || resistance <= 0f) return 0f
        return limit / (resistance + sqrt(dx * dx + dy * dy))
    }

    fun travelClampScale(x: Float, y: Float, limit: Float): Float {
        if (!x.isFinite() || !y.isFinite() || limit <= 0f) return 0f
        val length = sqrt(x * x + y * y)
        return if (length > limit) limit / length else 1f
    }

    fun lensScaleX(press: Float): Float = 1f + .055f * press.coerceIn(0f, 1f)
    fun lensScaleY(press: Float): Float = 1f + .07f * press.coerceIn(0f, 1f)
}

internal enum class HostNavigationIntent { NONE, SCRUB, ELASTIC }

/** DOWN only previews. Cancellation, multi-touch and vertical elastic movement never select a page. */
internal class HostNavigationGesture {
    private var active = false
    private var downIndex = -1
    private var canScrub = false
    var intent = HostNavigationIntent.NONE
        private set

    fun begin(index: Int, allowScrub: Boolean) {
        active = true
        downIndex = index
        canScrub = allowScrub
        intent = HostNavigationIntent.NONE
    }

    fun move(dx: Float, dy: Float, slop: Float): Boolean {
        if (!active || intent != HostNavigationIntent.NONE || !dx.isFinite() || !dy.isFinite()) return false
        if (dx * dx + dy * dy <= slop * slop) return false
        intent = if (canScrub && abs(dx) > abs(dy) * 1.15f) HostNavigationIntent.SCRUB
            else HostNavigationIntent.ELASTIC
        return true
    }

    fun finish(cancelled: Boolean, progress: Float, releaseIndex: Int, count: Int): Int? {
        if (!active) return null
        active = false
        if (cancelled) return null
        return when (intent) {
            HostNavigationIntent.SCRUB -> HostNavigationMotion.nearestPage(progress, count)
            HostNavigationIntent.NONE -> downIndex.takeIf { it == releaseIndex && it in 0 until count }
            HostNavigationIntent.ELASTIC -> null
        }
    }

    fun cancel() { active = false }
}
