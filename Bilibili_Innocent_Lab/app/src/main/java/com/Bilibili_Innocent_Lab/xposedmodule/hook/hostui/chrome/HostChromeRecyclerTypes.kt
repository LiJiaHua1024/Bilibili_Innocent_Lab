package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

/** Cache class metadata only; the bounded name walk also supports host/plugin class loaders. */
internal class HostChromeRecyclerTypes {
    private val types = HashMap<Class<*>, Boolean>()

    fun matches(type: Class<*>): Boolean {
        types[type]?.let { return it }
        var current: Class<*>? = type
        var remaining = 12
        while (current != null && remaining-- > 0) {
            if (current.name == "androidx.recyclerview.widget.RecyclerView") {
                if (types.size < 128) types[type] = true
                return true
            }
            current = current.superclass
        }
        if (types.size < 128) types[type] = false
        return false
    }
}
