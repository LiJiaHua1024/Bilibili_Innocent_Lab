package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ReplyTopologyKeywordFilterTest {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)

    @Test fun emptyQueryPreservesEveryOriginalPosition() {
        val graph = graph(node(201L), node(202L))
        assertArrayEquals(intArrayOf(0, 1, 2), ReplyTopologyKeywordFilter.resolve(graph, "  "))
        assertEquals("", ReplyTopologyKeywordFilter.normalize(null))
    }

    @Test fun authorMatchKeepsItsAncestorsAndDropsTheOtherBranch() {
        val graph = graph(node(201L, author = "Alice"), node(202L, author = "Bob"))
        assertArrayEquals(longArrayOf(100L, 201L), visible(graph, "alice"))
    }

    @Test fun messageMatchKeepsTheEntireParentChainInGraphOrder() {
        val graph = graph(node(201L), node(202L, parent = 201L, message = "needle"), node(203L))
        assertArrayEquals(longArrayOf(100L, 201L, 202L), visible(graph, "needle"))
    }

    @Test fun repliedAuthorCanMatchAndNullRepliedAuthorsAreSafe() {
        val graph = graph(node(201L), node(202L, replied = "Target"))
        assertArrayEquals(longArrayOf(100L, 202L), visible(graph, "target"))
    }

    @Test fun keywordsCannotJoinFieldsEvenWhenTheyContainNul() {
        val graph = graph(node(201L, author = "alpha", replied = "beta", message = "gamma"))
        for (query in listOf("alphabeta", "betagamma", "ha\u0000be", "ta\u0000ga")) {
            assertArrayEquals(query, intArrayOf(), ReplyTopologyKeywordFilter.resolve(graph, query))
        }
        assertArrayEquals(longArrayOf(100L, 201L), visible(graph, "gamma"))
    }

    @Test fun matchingSiblingsShareOnlyOneCopyOfTheirAncestor() {
        val graph = graph(node(201L), node(202L, parent = 201L, message = "hit"),
            node(203L, parent = 201L, message = "hit"), node(204L))
        assertArrayEquals(longArrayOf(100L, 201L, 202L, 203L), visible(graph, "hit"))
    }

    @Test fun normalizationDoesNotDependOnTheSystemLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i", ReplyTopologyKeywordFilter.normalize(" I "))
            assertArrayEquals(longArrayOf(100L, 201L), visible(graph(node(201L, author = "Iris")), " IRIS "))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun noMatchAndEmptyPlaceholderTextProduceNoRows() {
        val graph = graph(node(201L, flags = ReplyTopologyNodeFlags.FILTERED),
            node(202L, flags = ReplyTopologyNodeFlags.UNAVAILABLE))
        assertArrayEquals(intArrayOf(), ReplyTopologyKeywordFilter.resolve(graph, "missing"))
    }

    @Test fun filteringDoesNotRewriteGraphDataOrPurificationFlags() {
        val graph = graph(node(201L, message = "match", flags = ReplyTopologyNodeFlags.FILTERED), node(202L))
        val ids = graph.rpids.clone()
        val parents = graph.parentIndexes.clone()
        val flags = graph.flags.clone()
        ReplyTopologyKeywordFilter.resolve(graph, "match")
        assertArrayEquals(ids, graph.rpids)
        assertArrayEquals(parents, graph.parentIndexes)
        assertArrayEquals(flags, graph.flags)
        assertEquals("match", graph.messagePreviews[1])
    }

    @Test fun deepReplyChainsRemainIterativeAndRetainEveryAncestor() {
        val nodes = (1..3_000).map { offset ->
            node(100L + offset, parent = 99L + offset, message = if (offset == 3_000) "needle" else "")
        }
        val graph = graph(*nodes.toTypedArray())
        assertArrayEquals(IntArray(graph.size) { it }, ReplyTopologyKeywordFilter.resolve(graph, "needle"))
    }

    @Test fun filteredPositionsCanDifferFromTheirStableGraphIndexes() {
        val graph = graph(node(201L), node(202L), node(203L, message = "visible"))
        val indexes = ReplyTopologyKeywordFilter.resolve(graph, "visible")
        assertArrayEquals(intArrayOf(0, 3), indexes)
        assertEquals(203L, graph.rpids[indexes[1]])
        assertEquals(1, indexes.binarySearch(3))
        assertTrue(indexes.binarySearch(2) < 0)
    }

    private fun graph(vararg nodes: ReplyTopologyNodeSnapshot) = ReplyTopologyGraphBuilder.build(
        key, listOf(node(100L, parent = 0L)) + nodes
    )

    private fun visible(graph: ReplyTopologyGraph, query: String): LongArray =
        ReplyTopologyKeywordFilter.resolve(graph, query).map { graph.rpids[it] }.toLongArray()

    private fun node(id: Long, parent: Long = 100L, author: String = "", replied: String? = null,
        message: String = "", flags: Int = 0) = ReplyTopologyNodeSnapshot.fromRaw(
        rpid = id, rootRpid = if (id == 100L) 0L else 100L, parentRpid = parent,
        authorName = author, repliedAuthorName = replied, message = message, flags = flags
    )
}
