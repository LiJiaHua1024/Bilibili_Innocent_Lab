package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import java.lang.ref.WeakReference

/** A missing backdrop is temporary. Probe on page/layout changes and a bounded readiness window. */
internal class HostChromeBackdropRecovery<T : Any> {
    private var selected = WeakReference<T>(null)
    private var hadSelection = false
    private var pending = true
    private var attempts = 0
    private var nextAttempt = 0L
    private var lastAttempt = -MIN_INTERVAL_MS

    fun request(now: Long): Boolean {
        if (selected.get() != null) return false
        if (!pending) attempts = 0
        pending = true
        nextAttempt = maxOf(now, lastAttempt + MIN_INTERVAL_MS)
        return true
    }

    fun select(now: Long, usable: (T) -> Boolean, locate: () -> T?): T? {
        val current = selected.get()
        if (current != null) {
            if (usable(current)) return current
            selected.clear()
            pending = true; attempts = 0; nextAttempt = now
        } else if (hadSelection) {
            pending = true; attempts = 0; nextAttempt = now
        }
        hadSelection = false
        if (!pending || now < nextAttempt) return null
        val candidate = locate()?.takeIf(usable)
        lastAttempt = now
        if (candidate != null) {
            selected = WeakReference(candidate)
            hadSelection = true
            pending = false; attempts = 0
        } else {
            attempts++
            pending = attempts <= RETRY_DELAYS_MS.size
            if (pending) nextAttempt = now + RETRY_DELAYS_MS[attempts - 1]
        }
        return candidate
    }

    fun retryAfter(now: Long): Long? = if (pending) (nextAttempt - now).coerceAtLeast(0L) else null

    fun clear() {
        selected.clear(); hadSelection = false
        pending = true; attempts = 0; nextAttempt = 0L; lastAttempt = -MIN_INTERVAL_MS
    }

    private companion object {
        const val MIN_INTERVAL_MS = 64L
        val RETRY_DELAYS_MS = longArrayOf(64, 128, 256, 512, 1024)
    }
}
