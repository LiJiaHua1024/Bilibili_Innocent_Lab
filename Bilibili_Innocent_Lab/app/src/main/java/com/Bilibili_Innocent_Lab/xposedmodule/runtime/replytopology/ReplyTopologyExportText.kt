package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 只导出当前可见节点的预览；限制缩进与 UTF-16 长度，避免深树膨胀剪贴板事务。 */
internal object ReplyTopologyExportText {
    const val MAX_INDENT_DEPTH = 32
    const val MAX_TEXT_CHARS = 256_000

    data class Labels(
        val unknownAuthor: String,
        val emptyMessage: String,
        val filteredAuthor: String,
        val unavailableAuthor: String,
        val filteredMessage: String,
        val unavailableMessage: String
    )

    sealed interface Result {
        data class Ready(val text: String, val count: Int) : Result
        data object Empty : Result
        data object TooLarge : Result
    }

    fun render(graph: ReplyTopologyGraph?, indexes: IntArray, labels: Labels): Result {
        if (graph == null || indexes.isEmpty()) return Result.Empty
        val output = StringBuilder()
        for (index in indexes) {
            require(index in 0 until graph.size) { "Export index must belong to the current graph" }
            val flags = graph.flags[index]
            val filtered = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.FILTERED)
            val unavailable = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.PLACEHOLDER) ||
                ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.UNAVAILABLE)
            val author = graph.authorNames[index].ifBlank {
                when {
                    filtered -> labels.filteredAuthor
                    unavailable -> labels.unavailableAuthor
                    else -> labels.unknownAuthor
                }
            }
            val message = graph.messagePreviews[index].ifBlank {
                when {
                    filtered -> labels.filteredMessage
                    unavailable -> labels.unavailableMessage
                    else -> labels.emptyMessage
                }
            }
            val replied = graph.repliedAuthorNames[index]?.takeIf {
                it.isNotBlank() && !ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.ROOT)
            }
            val depth = graph.depths[index].coerceIn(0, MAX_INDENT_DEPTH)
            val length = depth * 2L + author.length + 2L + message.length +
                (if (replied == null) 0L else 3L + replied.length) + (if (output.isEmpty()) 0L else 1L)
            if (output.length + length > MAX_TEXT_CHARS) return Result.TooLarge
            if (output.isNotEmpty()) output.append('\n')
            repeat(depth) { output.append("  ") }
            output.append(author)
            if (replied != null) output.append(" → ").append(replied)
            output.append(": ").append(message)
        }
        return Result.Ready(output.toString().trimEnd(), indexes.size)
    }
}
