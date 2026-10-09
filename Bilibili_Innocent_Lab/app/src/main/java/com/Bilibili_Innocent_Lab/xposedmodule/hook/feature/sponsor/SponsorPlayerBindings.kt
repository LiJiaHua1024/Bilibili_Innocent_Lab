package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** 只关联同一构造链明确配对的实例；弱引用不延长宿主播放器和页面的生命周期。 */
internal class SponsorPlayerBindings {
    class Candidate {
        val epoch = AtomicLong()
        @Volatile var ready = false
        @Volatile var order = 0L
        @Volatile var wrapper = WeakReference<Any>(null)
        @Volatile var core = WeakReference<Any>(null)
        @Volatile var activity = WeakReference<Any>(null)
    }
    private val candidates = WeakHashMap<Any, Candidate>()
    private val pending = WeakHashMap<Any, WeakReference<Any>>()
    private var order = 0L

    @Synchronized fun wrapper(owner: Any, wrapper: Any, core: Any) {
        val candidate = candidates.getOrPut(owner, ::Candidate)
        if (candidate.wrapper.get()?.let { it !== wrapper } == true) {
            candidate.epoch.incrementAndGet()
            candidate.activity = WeakReference(null)
        }
        candidate.wrapper = WeakReference(wrapper)
        candidate.core = WeakReference(core)
        val wrapperActivity = pending.remove(wrapper)?.get()
        val coreActivity = pending.remove(core)?.get()
        (wrapperActivity ?: coreActivity)?.takeUnless {
            wrapperActivity != null && coreActivity != null && wrapperActivity !== coreActivity
        }?.let {
            candidate.activity = WeakReference(it)
        }
    }

    @Synchronized fun activity(owner: Any, activity: Any) {
        candidates.getOrPut(owner, ::Candidate).activity = WeakReference(activity)
    }

    @Synchronized fun container(core: Any, activity: Any): Boolean {
        val matches = candidates.values.filter { it.wrapper.get() === core || it.core.get() === core }
        if (matches.size != 1) {
            // 容器回调先到时等待明确的 wrapper 配对；歧义不能按“最后一个播放器”猜测。
            if (matches.isEmpty()) {
                if (pending.size >= 16) pending.remove(pending.keys.first())
                pending[core] = WeakReference(activity)
            }
            return false
        }
        matches.single().activity = WeakReference(activity)
        return true
    }

    @Synchronized fun begin(owner: Any): Long = candidates.getOrPut(owner, ::Candidate).let {
        it.ready = false; it.epoch.incrementAndGet()
    }

    @Synchronized fun ready(owner: Any, epoch: Long) {
        candidates[owner]?.takeIf { it.epoch.get() == epoch }?.let { it.ready = true; it.order = ++order }
    }

    @Synchronized fun release(owner: Any, epoch: Long) {
        candidates[owner]?.takeIf { it.epoch.get() == epoch }?.ready = false
    }

    @Synchronized fun select(activity: Any): Pair<Any, Candidate>? = candidates.entries.filter {
        it.value.ready && it.value.activity.get() === activity && it.value.wrapper.get() != null
    }.maxByOrNull { it.value.order }?.let { it.key to it.value }

    @Synchronized fun current(owner: Any, candidate: Candidate, epoch: Long, wrapper: Any, activity: Any): Boolean =
        candidates[owner] === candidate && candidate.ready && candidate.epoch.get() == epoch &&
            candidate.wrapper.get() === wrapper && candidate.activity.get() === activity

    @Synchronized fun destroy(activity: Any) {
        candidates.entries.removeAll { it.value.activity.get() === activity }
        pending.entries.removeAll { it.value.get() === activity || it.value.get() == null }
    }
}
