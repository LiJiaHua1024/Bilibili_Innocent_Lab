package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** Only the VIP source is an optional unlock target; DLC keeps its original entitlement checks. */
internal object BrandSplashPolicy {
    const val VIP_SOURCE = "vip"
    const val DLC_SOURCE = "dlc"

    fun mayUnlock(source: String?, forbidden: Boolean): Boolean = source == VIP_SOURCE && !forbidden

    fun <T> retainSelectedVip(
        server: List<T>,
        selected: List<T>,
        source: (T) -> String?,
        key: (T) -> String,
        identity: (T) -> String = key
    ): List<T> {
        val present = server.mapTo(HashSet(), key)
        val paidIds = server.filter { source(it) == DLC_SOURCE }.mapTo(HashSet(), identity)
        var merged: ArrayList<T>? = null
        selected.forEach { item ->
            if (source(item) == VIP_SOURCE && identity(item) !in paidIds && present.add(key(item))) {
                val output = merged ?: ArrayList(server).also { merged = it }
                output.add(item)
            }
        }
        return merged ?: server
    }
}

/** An equivalent host write must resume a pending rotation as well as the initial load. */
internal data class BrandSplashImageReload(val afterKey: String?)

internal fun brandSplashImageReloadAfterWrite(
    signature: String,
    cachedSignature: String?,
    rotationPending: Boolean,
    cachedKey: String?
): BrandSplashImageReload? = when {
    signature.isEmpty() -> null
    cachedSignature != signature -> BrandSplashImageReload(null)
    rotationPending && cachedKey != null -> BrandSplashImageReload(cachedKey)
    else -> null
}

/** Reentrant scopes unwind even when the original host callback fails. */
internal class BrandSplashScopes<T : Any> {
    private val frames = ThreadLocal<ArrayDeque<T>>()
    fun enter(value: T) {
        val stack = frames.get() ?: ArrayDeque<T>().also(frames::set)
        stack.addLast(value)
    }
    fun current(): T? = frames.get()?.lastOrNull()
    fun leave() {
        val stack = frames.get() ?: return
        if (stack.isNotEmpty()) stack.removeLast()
        if (stack.isEmpty()) frames.remove()
    }
}

/** Per-process intent comes from the actual host storage, and successful writes replace it. */
internal class BrandSplashSelectionState<T : Any> {
    data class Snapshot<T>(val revision: Long, val selected: List<T>, val customMode: Boolean)
    private var initialized = false
    private var revision = 0L
    private var selected = emptyList<T>()
    private var mode = false
    private var modeKnown = false

    @Synchronized fun initialize(items: List<T>, customMode: Boolean): Snapshot<T> {
        if (!initialized) {
            initialized = true
            selected = items.toList()
            if (!modeKnown) mode = customMode
            modeKnown = true
            revision++
        }
        return snapshot()
    }

    @Synchronized fun observeWrite(items: List<T>): Snapshot<T> {
        initialized = true
        selected = items.toList()
        revision++
        return snapshot()
    }

    @Synchronized fun observeMode(customMode: Boolean): Snapshot<T> {
        mode = customMode
        modeKnown = true
        if (!customMode) selected = emptyList()
        revision++
        return snapshot()
    }

    @Synchronized fun isInitialized(): Boolean = initialized
    @Synchronized fun snapshot(): Snapshot<T> = Snapshot(revision, selected, mode)
    @Synchronized fun isCurrent(candidateRevision: Long): Boolean = candidateRevision == revision
}

/** The sentinel is written before the callback, so re-entry cannot fire a second exit. */
internal object BrandSplashCoroutineSkip {
    const val COMPLETED_LABEL = Int.MAX_VALUE
    fun complete(label: Int, setLabel: (Int) -> Unit, exit: () -> Unit): Boolean {
        if (label == COMPLETED_LABEL) return true
        if (label != 0) return false
        setLabel(COMPLETED_LABEL)
        try {
            exit()
        } catch (failure: Throwable) {
            setLabel(0)
            throw failure
        }
        return true
    }
}
