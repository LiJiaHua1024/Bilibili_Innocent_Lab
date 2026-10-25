package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

/** One active job plus the latest pending job per owner; shared executors keep their FIFO fairness. */
internal class HostLatestWorkQueue(private val execute: (Runnable) -> Unit) {
    private val lock = Any()
    private var pending: Runnable? = null
    private var scheduled = false
    private val dispatch = Runnable {
        val work = synchronized(lock) { pending.also { pending = null } }
        try { work?.run() } finally {
            val again = synchronized(lock) {
                if (pending == null) { scheduled = false; false } else true
            }
            if (again) schedule()
        }
    }

    fun submit(work: Runnable) {
        val start = synchronized(lock) {
            pending = work
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) schedule()
    }

    /** In-flight results still use the owner's generation check; queued work releases its inputs. */
    fun clear() = synchronized(lock) { pending = null }

    private fun schedule() {
        try { execute(dispatch) } catch (failure: Throwable) {
            synchronized(lock) { scheduled = false; pending = null }
            throw failure
        }
    }
}
