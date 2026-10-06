package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import java.util.Locale

/** 只生成显示子集，不改图或内容净化的 FILTERED 标记；命中节点的祖先一起保留。 */
internal object ReplyTopologyKeywordFilter {
    fun normalize(query: String?): String = query?.trim()?.lowercase(Locale.ROOT).orEmpty()

    fun resolve(graph: ReplyTopologyGraph, query: String): IntArray {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return IntArray(graph.size) { it }
        val included = BooleanArray(graph.size)
        for (index in 0 until graph.size) {
            // 分字段匹配，连含 NUL 的输入也不能跨作者／正文边界命中。
            if (graph.authorNames[index].lowercase(Locale.ROOT).contains(normalized) ||
                graph.repliedAuthorNames[index]?.lowercase(Locale.ROOT)?.contains(normalized) == true ||
                graph.messagePreviews[index].lowercase(Locale.ROOT).contains(normalized)
            ) {
                var cursor = index
                while (cursor in included.indices && !included[cursor]) {
                    included[cursor] = true
                    cursor = graph.parentIndexes[cursor]
                }
            }
        }
        val indexes = IntArray(included.count { it })
        var output = 0
        included.forEachIndexed { index, keep -> if (keep) indexes[output++] = index }
        return indexes
    }
}
