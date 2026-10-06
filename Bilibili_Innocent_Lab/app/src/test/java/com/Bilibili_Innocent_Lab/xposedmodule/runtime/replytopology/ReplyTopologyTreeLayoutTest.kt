package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTreeLayoutTest {
    private val key = ReplyTopologyThreadKey(1L, 1L, 100L)

    @Test fun fullTreeRetainsEveryTrueDepthAndParentLink() {
        val graph = chain(1_000)
        val original = graph.depths.clone()
        val layout = ReplyTopologyTreeLayout(graph)
        assertEquals(1_001, layout.size)
        assertEquals(1_000, layout.maxDepth)
        assertEquals(999, layout.parentRow(1_000))
        assertEquals(1_000, layout.depthAt(1_000))
        assertArrayEquals(original, graph.depths)
    }

    @Test fun localPathRetainsSixAncestorsAndDirectChildrenButNotGrandchildren() {
        val graph = ReplyTopologyGraphBuilder.build(key, chainNodes(20) +
            listOf(node(121L, 120L), node(122L, 120L), node(123L, 121L)))
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 120L))
        assertEquals(14, path.omittedAncestors)
        assertEquals(14, path.baseDepth)
        val ids = path.indexes.map { graph.rpids[it] }
        assertTrue(ids.containsAll((114L..122L).toList()))
        assertFalse(ids.contains(123L))
        assertFalse(ids.contains(113L))
        val layout = ReplyTopologyTreeLayout(graph, path.indexes, path.baseDepth)
        assertEquals(-1, layout.parentRow(0))
        assertEquals(6, layout.depthAt(layout.rowOf(120L)))
        assertEquals(7, layout.depthAt(layout.rowOf(121L)))
    }

    @Test fun shallowPathHasNoOmittedAncestorsAndMissingNodeReturnsNull() {
        val graph = chain(3)
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 103L))
        assertEquals(0, path.omittedAncestors)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), path.indexes)
        assertNull(ReplyTopologyPath.resolve(graph, 999L))
    }

    @Test fun branchesAndPlaceholderParentsUseGraphIdentityRatherThanAuthorText() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L), node(201L, 999L), node(202L, 999L)))
        val path = requireNotNull(ReplyTopologyPath.resolve(graph, 999L))
        assertTrue(path.indexes.map { graph.rpids[it] }.containsAll(listOf(100L, 999L, 201L, 202L)))
        val layout = ReplyTopologyTreeLayout(graph)
        val parent = layout.rowOf(999L)
        assertEquals(parent, layout.parentRow(layout.rowOf(201L)))
        assertEquals(layout.rowOf(202L), layout.lastChildRows[parent])
    }

    @Test fun viewportCullingIncludesPartialCardsButDoesNotScanAnEmptyRegion() {
        val layout = ReplyTopologyTreeLayout(chain(20))
        assertEquals(4..6, layout.visibleRows(500.0, 650.0, 100.0, 80.0))
        assertEquals(0..0, layout.visibleRows(-40.0, 20.0, 100.0, 80.0))
        assertTrue(layout.visibleRows(10_000.0, 20_000.0, 100.0, 80.0).isEmpty())
        assertTrue(layout.visibleRows(-200.0, -100.0, 100.0, 80.0).isEmpty())
    }

    @Test fun subsetIndexesCannotDuplicateOrReorderStableNodes() {
        val graph = chain(3)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(2, 1)) }.isFailure)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(1, 1)) }.isFailure)
        assertTrue(runCatching { ReplyTopologyTreeLayout(graph, intArrayOf(50)) }.isFailure)
    }

    private fun chain(count: Int) = ReplyTopologyGraphBuilder.build(key, chainNodes(count))
    @Test fun indexedBranchesMatchBruteForceIncludingParentsAboveTheViewport() {
        val random = java.util.Random(812L)
        val nodes = (0..500).map { id -> node(100L + id, if (id == 0) 0L else 100L + random.nextInt(id)) }
        val layout = ReplyTopologyTreeLayout(ReplyTopologyGraphBuilder.build(key, nodes))
        for (first in 0 until layout.size step 7) {
            val last = minOf(first + 9, layout.size - 1)
            val expected = (0..last).filter { layout.lastChildRows[it] >= first }.toIntArray()
            val count = layout.collectVisibleBranches(first, last)
            assertArrayEquals(expected, layout.visibleBranchRows.copyOf(count))
        }
        assertEquals(0, layout.collectVisibleBranches(-1, -1))
        assertEquals(0, layout.collectVisibleBranches(layout.size, layout.size + 1))
    }

    @Test fun longRootTrunkIsRetainedWithoutIncludingUnrelatedCompletedBranches() {
        val graph = ReplyTopologyGraphBuilder.build(key, listOf(node(100L, 0L)) +
            (1..10_000).map { node(100L + it, 100L) })
        val layout = ReplyTopologyTreeLayout(graph)
        assertEquals(1, layout.collectVisibleBranches(9_000, 9_010))
        assertEquals(0, layout.visibleBranchRows[0])
        assertEquals(9_990, layout.rowOf(10_090L))
    }

    @Test fun reusedPathIndexPreservesDirectChildrenAndDoesNotIncludeOtherBranches() {
        val graph = ReplyTopologyGraphBuilder.build(key, chainNodes(20) +
            (1..2_000).map { node(1_000L + it, 100L) } + listOf(node(900L, 120L), node(901L, 900L)))
        val index = ReplyTopologyPath.Index(graph)
        repeat(10) {
            val path = requireNotNull(index.resolve(120L))
            assertEquals(8, path.indexes.size)
            assertTrue(path.indexes.map { graph.rpids[it] }.contains(900L))
            assertFalse(path.indexes.map { graph.rpids[it] }.contains(901L))
            assertEquals(14, path.omittedAncestors)
        }
        assertNull(index.resolve(Long.MAX_VALUE))
        assertEquals(1, requireNotNull(index.resolve(120L, 0)).indexes.count { graph.rpids[it] == 120L })
        assertTrue(runCatching { index.resolve(120L, -1) }.isFailure)
    }

    private fun chainNodes(count: Int) = (0..count).map { node(100L + it, if (it == 0) 0L else 99L + it) }
    private fun node(id: Long, parent: Long) = ReplyTopologyNodeSnapshot.fromRaw(id,
        if (id == 100L) 0L else 100L, parent, authorName = "same author", message = "reply")
}
