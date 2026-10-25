package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeModifierOwnershipTest {
    private class Node(var modifier: Any)

    @Test fun `material failure can restore surface background and divider independently`() {
        val ownership = HostChromeModifierOwnership()
        val nodes = List(3) { Node(Any()) }
        val originals = nodes.map { it.modifier }
        nodes.forEach { node ->
            val original = node.modifier
            node.modifier = Any()
            ownership.record(node, node.modifier, original)
        }
        val leases = ownership.drain()
        assertEquals(3, leases.size)
        leases.forEach { (native, expected, original) ->
            val node = native as Node
            if (node.modifier === expected) node.modifier = original
        }
        nodes.forEachIndexed { index, node -> assertSame(originals[index], node.modifier) }
        assertTrue(ownership.drain().isEmpty())
    }

    @Test fun `recomposition renews only its node and host replacement keeps ownership`() {
        val ownership = HostChromeModifierOwnership()
        val original = Any()
        val node = Node(Any())
        ownership.record(node, node.modifier, original)
        val nextOriginal = Any()
        node.modifier = Any()
        ownership.record(node, node.modifier, nextOriginal)
        val retained = Node(Any())
        ownership.record(retained, retained.modifier, Any())
        val hostReplacement = Any()
        retained.modifier = hostReplacement
        val leases = ownership.drain()
        assertEquals(2, leases.size)
        leases.forEach { (native, expected, previous) ->
            val target = native as Node
            if (target.modifier === expected) target.modifier = previous
        }
        assertSame(nextOriginal, node.modifier)
        assertSame(hostReplacement, retained.modifier)
    }
}
