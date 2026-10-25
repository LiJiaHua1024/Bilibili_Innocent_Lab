package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The right actions own decorative backplates as well as protected icon/image content. */
internal class HostChromeBackdropGate {
    private val foreground = HashMap<Any, Boolean>()
    private var protectedPaint = 0
    val decorative: Boolean get() = protectedPaint == 0
    val decorativeImage: Boolean get() = foreground.isEmpty()

    fun enterForeground(allowBackdropPaint: Boolean = false): Any = Any().also {
        foreground[it] = allowBackdropPaint
        if (!allowBackdropPaint) protectedPaint++
    }
    fun exitForeground(token: Any) {
        if (foreground.remove(token) == false) protectedPaint--
    }

    companion object {
        // Registrar deduplication keeps earlier callbacks after a partial installation. A retry
        // must arm those callbacks too. Values contain strings/flags, never a Class or host View.
        private val installations = WeakHashMap<Class<*>, MutableMap<List<String>, AtomicBoolean>>()

        fun installation(owner: Class<*>, method: String, parameters: List<String>): AtomicBoolean =
            synchronized(installations) {
                installations.getOrPut(owner) { HashMap() }.getOrPut(listOf(method) + parameters) { AtomicBoolean(false) }
            }
    }
}
