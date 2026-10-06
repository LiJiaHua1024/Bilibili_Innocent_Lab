package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

internal object HostVideoCardStyleSpec {
    fun coverRadius(width: Int, height: Int): Float = minOf(width, height).coerceAtLeast(0) * 0.18f
}

/** 保存宿主原始留白，复用/换列时不叠加；非双列恢复原值。 */
internal class HostVideoCardSpacing(
    private val originalLeft: Int,
    private val originalTop: Int,
    private val originalRight: Int,
    private val originalBottom: Int,
    density: Float
) {
    private val outer = (14 * density).toInt()
    private val inner = (7 * density).toInt()
    private val extraTop = (5 * density).toInt()
    private val extraBottom = (12 * density).toInt()

    fun left(span: Int): Int = originalLeft + when (span) { 0 -> outer; 1 -> inner; else -> 0 }
    fun right(span: Int): Int = originalRight + when (span) { 0 -> inner; 1 -> outer; else -> 0 }
    fun top(span: Int): Int = originalTop + if (span in 0..1) extraTop else 0
    fun bottom(span: Int): Int = originalBottom + if (span in 0..1) extraBottom else 0
}

/** 位置变化不属于形状变化；同尺寸复用无需重建背景和阴影轮廓。 */
internal class HostVideoCardGeometry {
    private var width = -1
    private var height = -1
    var radius = -1f
        private set

    fun update(width: Int, height: Int, radius: Float): Boolean {
        if (this.width == width && this.height == height && this.radius == radius) return false
        this.width = width
        this.height = height
        this.radius = radius
        return true
    }
}
