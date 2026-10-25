package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Each native node owns its own restoration lease, including multiple decorative siblings. */
internal class HostChromeModifierOwnership {
    private data class Owned(val expected: WeakReference<Any>, val original: WeakReference<Any>)
    data class Lease(val node: Any, val expected: Any, val original: Any)
    private val owners = WeakHashMap<Any, Owned>()

    fun record(node: Any, expected: Any, original: Any) {
        // The live decorated Modifier keeps its original in the painter. This index must not
        // retain a native node indirectly through callbacks captured by that original.
        owners[node] = Owned(WeakReference(expected), WeakReference(original))
    }

    fun drain(): List<Lease> {
        val leases = owners.mapNotNull { (node, entry) ->
            val expected = entry.expected.get()
            val original = entry.original.get()
            if (expected != null && original != null) Lease(node, expected, original) else null
        }
        owners.clear()
        return leases
    }
}
