package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** The outer draw boundary suppresses background AND image content without changing layout. */
internal object HostChromeBackdropPaint {
    fun suppress(original: Any, empty: Any, withContent: Method, then: Method,
                 function: Class<*>, unit: Any, active: () -> Boolean, drawContent: (Any) -> Unit): Any {
        val skip = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    // Image composables may derive another Modifier before applying it. Their
                    // boundary still restores native paint immediately if the material fails.
                    if (!active()) args?.firstOrNull()?.let(drawContent)
                    unit
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BIL native backdrop paint boundary"
                else -> null
            }
        }
        // Appending after the native fill leaves that fill visible. Prefix before every native draw.
        val boundary = requireNotNull(withContent.invoke(null, empty, skip))
        return requireNotNull(then.invoke(boundary, original))
    }
}
