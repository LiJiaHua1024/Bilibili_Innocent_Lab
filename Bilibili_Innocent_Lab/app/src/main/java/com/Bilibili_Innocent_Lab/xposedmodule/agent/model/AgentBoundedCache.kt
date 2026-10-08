package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

/** 只用于来源摘要与能力/兼容元数据；禁止存放任务输入、图像和凭据。 */
internal class AgentBoundedCache<K, V>(
    private val capacity: Int = 96,
    private val ttlMs: Long = AgentModelCapabilities.VALID_FOR_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry<V>(val value: V, val savedAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    init { require(capacity > 0 && ttlMs > 0) }

    @Synchronized operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        val now = clock()
        if (now < entry.savedAt || now - entry.savedAt > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized operator fun set(key: K, value: V) {
        val now = clock()
        entries.entries.removeAll { (_, entry) -> now < entry.savedAt || now - entry.savedAt > ttlMs }
        entries[key] = Entry(value, now)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun size(): Int = entries.size
}
