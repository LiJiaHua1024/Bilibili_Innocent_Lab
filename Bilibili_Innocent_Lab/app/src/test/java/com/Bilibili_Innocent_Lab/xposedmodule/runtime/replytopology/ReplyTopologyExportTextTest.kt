package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyExportTextTest {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)
    private val labels = ReplyTopologyExportText.Labels("unknown", "empty", "filtered author",
        "unavailable author", "filtered reply", "unavailable reply")

    @Test fun depthAndRepliedAuthorMatchTheVisibleTitles() {
        val graph = graph(node(100L, 0L, "root", "root text", "ignored"),
            node(201L, 100L, "child", "child text", "root"),
            node(202L, 201L, "leaf", "leaf text", "child"))
        val result = ready(graph)
        assertEquals("root: root text\n  child → root: child text\n    leaf → child: leaf text", result.text)
        assertEquals(3, result.count)
        assertFalse(result.text.endsWith('\n'))
    }

    @Test fun onlyTheFilteredVisibleSetAndItsAncestorsAreExported() {
        val graph = graph(node(100L, 0L, "root", ""), node(201L, 100L, "other", "omit"),
            node(202L, 100L, "selected", "needle"))
        val indexes = ReplyTopologyKeywordFilter.resolve(graph, "needle")
        val result = ReplyTopologyExportText.render(graph, indexes, labels) as ReplyTopologyExportText.Result.Ready
        assertEquals("root: empty\n  selected: needle", result.text)
        assertEquals(2, result.count)
        assertFalse(result.text.contains("omit"))
    }

    @Test fun blankAuthorsAndMessagesHaveLocalizedFallbacks() {
        assertEquals("unknown: empty", ready(graph(node(100L, 0L, "", ""))).text)
    }

    @Test fun unavailableAndPurifiedPlaceholdersRemainRecognizable() {
        val graph = graph(node(100L, 0L, "root", "root"),
            node(201L, 100L, "", "", flags = ReplyTopologyNodeFlags.FILTERED),
            node(202L, 100L, "", "", flags = ReplyTopologyNodeFlags.UNAVAILABLE))
        assertEquals("root: root\n  filtered author: filtered reply\n  unavailable author: unavailable reply",
            ready(graph).text)
    }

    @Test fun absentGraphAndEmptyDisplaySetReturnEmpty() {
        assertSame(ReplyTopologyExportText.Result.Empty, ReplyTopologyExportText.render(null, intArrayOf(), labels))
        assertSame(ReplyTopologyExportText.Result.Empty,
            ReplyTopologyExportText.render(graph(node(100L, 0L, "", "")), intArrayOf(), labels))
    }

    @Test fun deepTreesDoNotExpandIndentationWithoutBound() {
        val nodes = (0..100).map { offset ->
            node(100L + offset, if (offset == 0) 0L else 99L + offset, "user", "text")
        }
        val text = ready(graph(*nodes.toTypedArray())).text
        val last = text.lineSequence().last()
        assertEquals(ReplyTopologyExportText.MAX_INDENT_DEPTH * 2, last.takeWhile { it == ' ' }.length)
    }

    @Test fun oversizedExportsFailBeforeReturningATruncatedTree() {
        val nodes = (0..1_500).map { offset ->
            node(100L + offset, if (offset == 0) 0L else 100L, "a".repeat(48), "😀".repeat(120))
        }
        val graph = graph(*nodes.toTypedArray())
        assertSame(ReplyTopologyExportText.Result.TooLarge,
            ReplyTopologyExportText.render(graph, IntArray(graph.size) { it }, labels))
    }

    @Test fun exportUsesTheBoundedPreviewAndDoesNotFetchOrRestoreFullText() {
        val graph = graph(node(100L, 0L, "root", "a".repeat(300)))
        val result = ready(graph)
        assertTrue(result.text.endsWith('…'))
        assertTrue(result.text.length < 160)
    }

    private fun ready(graph: ReplyTopologyGraph) = ReplyTopologyExportText.render(
        graph, IntArray(graph.size) { it }, labels
    ) as ReplyTopologyExportText.Result.Ready

    private fun graph(vararg nodes: ReplyTopologyNodeSnapshot) = ReplyTopologyGraphBuilder.build(key, nodes.asList())

    private fun node(id: Long, parent: Long, author: String, message: String, replied: String? = null,
        flags: Int = 0) = ReplyTopologyNodeSnapshot.fromRaw(
        rpid = id, rootRpid = if (id == 100L) 0L else 100L, parentRpid = parent,
        authorName = author, repliedAuthorName = replied, message = message, flags = flags
    )
}
