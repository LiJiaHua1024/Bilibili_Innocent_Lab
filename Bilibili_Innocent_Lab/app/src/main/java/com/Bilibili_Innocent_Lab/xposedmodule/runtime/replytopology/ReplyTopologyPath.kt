package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 局部路径只生成索引，不修改真实深度、父子关系或主面板的筛选。 */
internal object ReplyTopologyPath {
    data class Selection(val indexes: IntArray, val baseDepth: Int, val omittedAncestors: Int)

    fun resolve(graph: ReplyTopologyGraph, rpid: Long, ancestorLimit: Int = 6): Selection? {
        require(ancestorLimit >= 0)
        val target = graph.rpids.indexOf(rpid)
        if (target < 0) return null
        val included = BooleanArray(graph.size)
        included[target] = true
        var cursor = graph.parentIndexes[target]
        var ancestors = 0
        while (cursor in included.indices && !included[cursor] && ancestors < ancestorLimit) {
            included[cursor] = true
            ancestors++
            cursor = graph.parentIndexes[cursor]
        }
        for (index in 0 until graph.size) {
            if (graph.parentIndexes[index] == target) included[index] = true
        }
        val indexes = IntArray(included.count { it })
        var output = 0
        included.forEachIndexed { index, keep -> if (keep) indexes[output++] = index }
        val depth = indexes.minOfOrNull { graph.depths[it] }?.coerceAtLeast(0) ?: 0
        return Selection(indexes, depth, (graph.depths[target] - ancestors).coerceAtLeast(0))
    }
}
