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
    private val rowsById = HashMap<Long, Int>(indexes.size)
    val lastChildRows = IntArray(indexes.size) { -1 }
    private var branchLeafCount = 1
    private val branchEnds: IntArray
    /** 查询输出复用；调用方消费完毕才可进行下一次查询。 */
    val visibleBranchRows = IntArray(indexes.size)
    val size: Int get() = indexes.size
    val maxDepth: Int

    init {
        require(baseDepth >= 0)
        var previous = -1
        var deepest = 0
        this.indexes.forEachIndexed { row, index ->
            require(index in 0 until graph.size && index > previous) { "Tree indexes must be valid and ascending" }
            rows[index] = row
            rowsById[graph.rpids[index]] = row
            deepest = maxOf(deepest, (graph.depths[index] - baseDepth).coerceAtLeast(0))
            previous = index
        }
        maxDepth = deepest
        this.indexes.forEachIndexed { row, index ->
            val parent = graph.parentIndexes[index]
            if (parent in rows.indices && rows[parent] >= 0) lastChildRows[rows[parent]] = row
        }
        while (branchLeafCount < size) branchLeafCount *= 2
        branchEnds = IntArray(branchLeafCount * 2) { -1 }
        for (row in 0 until size) branchEnds[branchLeafCount + row] = lastChildRows[row]
        for (slot in branchLeafCount - 1 downTo 1) branchEnds[slot] = maxOf(branchEnds[slot * 2], branchEnds[slot * 2 + 1])
    }

    fun indexAt(row: Int): Int = indexes.getOrNull(row) ?: -1
    fun rowOf(rpid: Long): Int = rowsById[rpid] ?: -1
    fun depthAt(row: Int): Int = (graph.depths[indexes[row]] - baseDepth).coerceAtLeast(0)
    fun parentRow(row: Int): Int = graph.parentIndexes[indexes[row]].let { rows.getOrNull(it) ?: -1 }

    /** 包含视野上方但仍连接到屏内的父干线；区间树跳过完全不相交的分支。 */
    fun collectVisibleBranches(first: Int, last: Int): Int {
        if (first < 0 || last < first || first >= size) return 0
        return collectBranches(1, 0, branchLeafCount - 1, first, minOf(last, size - 1), 0)
    }

    private fun collectBranches(slot: Int, low: Int, high: Int, first: Int, last: Int, output: Int): Int {
        if (low > last || branchEnds[slot] < first) return output
        if (low == high) {
            visibleBranchRows[output] = low
            return output + 1
        }
        val middle = (low + high) ushr 1
        val next = collectBranches(slot * 2, low, middle, first, last, output)
        return collectBranches(slot * 2 + 1, middle + 1, high, first, last, next)
    }

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
