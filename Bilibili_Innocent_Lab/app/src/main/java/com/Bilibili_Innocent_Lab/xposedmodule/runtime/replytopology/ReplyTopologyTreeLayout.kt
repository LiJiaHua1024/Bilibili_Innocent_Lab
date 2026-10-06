package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import kotlin.math.floor

/** 虚拟树画布：真实深度决定列，先序决定行，不生成每节点 View 或巨幅 Bitmap。 */
internal class ReplyTopologyTreeLayout(
    val graph: ReplyTopologyGraph,
    indexes: IntArray = IntArray(graph.size) { it },
    val baseDepth: Int = 0
) {
    val indexes = indexes.copyOf()
    private val rows = IntArray(graph.size) { -1 }
    val lastChildRows = IntArray(indexes.size) { -1 }
    val size: Int get() = indexes.size
    val maxDepth: Int

    init {
        require(baseDepth >= 0)
        var previous = -1
        var deepest = 0
        this.indexes.forEachIndexed { row, index ->
            require(index in 0 until graph.size && index > previous) { "Tree indexes must be valid and ascending" }
            rows[index] = row
            deepest = maxOf(deepest, (graph.depths[index] - baseDepth).coerceAtLeast(0))
            previous = index
        }
        maxDepth = deepest
        this.indexes.forEachIndexed { row, index ->
            val parent = graph.parentIndexes[index]
            if (parent in rows.indices && rows[parent] >= 0) lastChildRows[rows[parent]] = row
        }
    }

    fun indexAt(row: Int): Int = indexes.getOrNull(row) ?: -1
    fun rowOf(rpid: Long): Int = graph.rpids.indexOf(rpid).let { if (it >= 0) rows[it] else -1 }
    fun depthAt(row: Int): Int = (graph.depths[indexes[row]] - baseDepth).coerceAtLeast(0)
    fun parentRow(row: Int): Int = graph.parentIndexes[indexes[row]].let { rows.getOrNull(it) ?: -1 }

    fun visibleRows(top: Double, bottom: Double, rowStep: Double, cardHeight: Double): IntRange {
        if (size == 0 || !top.isFinite() || !bottom.isFinite() || bottom < top ||
            !rowStep.isFinite() || !cardHeight.isFinite() || rowStep <= 0.0 || cardHeight <= 0.0 ||
            bottom < 0.0 || top > (size - 1) * rowStep + cardHeight
        ) return IntRange.EMPTY
        val first = floor((top - cardHeight) / rowStep).toInt().coerceIn(0, size - 1)
        val last = floor(bottom / rowStep).toInt().coerceIn(0, size - 1)
        return if (first <= last) first..last else IntRange.EMPTY
    }
}
