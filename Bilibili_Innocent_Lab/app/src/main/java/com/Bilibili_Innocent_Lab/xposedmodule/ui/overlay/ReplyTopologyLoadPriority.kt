package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

/** 可见行始终先于邻近行；只返回有界窗口，不按整张树的顺序逐项预热。 */
internal object ReplyTopologyLoadPriority {
    fun intersects(left: Double, top: Double, right: Double, bottom: Double, width: Double, height: Double): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width > 0.0 && height > 0.0 && right >= left && bottom >= top &&
            right >= 0.0 && left <= width && bottom >= 0.0 && top <= height

    fun rows(size: Int, first: Int, last: Int, nearby: Int = 4, limit: Int = 96): IntArray {
        if (size <= 0 || first < 0 || last < first || first >= size || limit <= 0) return IntArray(0)
        val end = minOf(last, size - 1)
        val radius = nearby.coerceIn(0, limit)
        val count = minOf(limit.toLong(), end.toLong() - first + 1 + minOf(first, radius) + minOf(size - 1 - end, radius)).toInt()
        val result = IntArray(count)
        var output = 0
        var row = first
        while (row <= end && output < count) result[output++] = row++
        for (distance in 1..radius) {
            if (first >= distance && output < count) result[output++] = first - distance
            if (distance < size - end && output < count) result[output++] = end + distance
        }
        return result
    }
}
