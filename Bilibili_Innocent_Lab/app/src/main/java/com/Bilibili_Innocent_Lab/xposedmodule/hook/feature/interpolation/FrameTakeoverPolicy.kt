package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Reentrant adapter calls must not clear their outer main-player scope. */
internal class FrameCallScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun enter() { depth.set((depth.get() ?: 0) + 1) }
    fun leave() { val next = (depth.get() ?: 0) - 1; if (next <= 0) depth.remove() else depth.set(next) }
}

/** Both ends are weak; identity, rather than host-defined equals(), determines ownership. */
internal class FrameSurfaceOwnership<O : Any, S : Any>(private val maxOwners: Int = 16) {
    private class Binding<O : Any, S : Any>(owner: O, surface: S?) {
        val owner = WeakReference(owner)
        var surface = WeakReference(surface)
    }
    private val bindings = ArrayList<Binding<O, S>>()
    @Synchronized fun bind(owner: O, surface: S?, inMainPlayer: Boolean): Boolean {
        bindings.removeAll { it.owner.get() == null }
        val binding = bindings.firstOrNull { it.owner.get() === owner }
        if (!inMainPlayer && binding == null) return false
        if (binding != null) binding.surface = WeakReference(surface)
        else {
            if (bindings.size >= maxOwners) return false
            bindings += Binding(owner, surface)
        }
        return true
    }
    @Synchronized fun contains(surface: S): Boolean = bindings.any { it.owner.get() != null && it.surface.get() === surface }
    @Synchronized fun release(owner: O) { bindings.removeAll { it.owner.get() == null || it.owner.get() === owner } }
}

/** A released or reconfigured decoder cannot acquire a bridge from an older async preparation. */
internal class FrameDecoderGeneration<C : Any> {
    class Ticket internal constructor() {
        @Volatile private var active = true
        fun isActive(): Boolean = active
        internal fun revoke() { active = false }
    }
    private val current = WeakHashMap<C, Ticket>()
    @Synchronized fun begin(codec: C, onReplace: () -> Unit = {}): Ticket =
        Ticket().also { current.put(codec, it)?.revoke(); onReplace() }
    @Synchronized fun end(codec: C, ticket: Ticket? = null, action: () -> Unit = {}): Boolean {
        if (ticket != null && current[codec] !== ticket) return false
        current.remove(codec)?.revoke()
        action()
        return true
    }
    @Synchronized fun matches(codec: C, ticket: Ticket): Boolean = current[codec] === ticket
    @Synchronized fun replace(codec: C, expected: Ticket, action: (Ticket) -> Unit): Ticket? {
        if (current[codec] !== expected) return null
        expected.revoke()
        return Ticket().also { current[codec] = it; action(it) }
    }
    @Synchronized fun commit(codec: C, ticket: Ticket, action: () -> Unit): Boolean {
        if (current[codec] !== ticket) return false
        action()
        return true
    }
}

/** Store before checking readiness, so a Ready callback racing this insertion cannot lose it. */
internal class FramePendingTakeovers<C : Any, D : Any> {
    private val waiting = WeakHashMap<C, D>()
    fun await(codec: C, decoder: D, isReady: () -> Boolean, wake: () -> Unit) {
        synchronized(this) { waiting[codec] = decoder }
        if (isReady()) wake()
    }
    @Synchronized fun remove(codec: C) { waiting.remove(codec) }
    @Synchronized fun hasPending(): Boolean = waiting.isNotEmpty()
    @Synchronized fun take(): List<Pair<C, D>> =
        waiting.entries.map { it.key to it.value }.also { waiting.clear() }
}

internal object FrameCodecConfigureShape {
    fun cryptoIndex(parameters: List<String>): Int? {
        if (parameters.size != 4 || parameters[0] != "android.media.MediaFormat" ||
            parameters[1] != "android.view.Surface") return null
        return when {
            parameters[2] == "android.media.MediaCrypto" && parameters[3] == "int" -> 2
            parameters[2] == "int" && parameters[3] == "android.media.MediaDescrambler" -> 3
            else -> null
        }
    }
}

internal object FrameHookCoverage {
    private val required = setOf("display", "op", "surface", "configure", "flush", "stop", "release", "audio.ctor")
    fun complete(installed: Set<String>): Boolean = installed.containsAll(required)
}
