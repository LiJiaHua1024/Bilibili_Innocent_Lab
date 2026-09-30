package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 语义判定分数缓存：**W-TinyLFU**（窗口 LRU + 分段 LRU 主区 + TinyLFU 准入）。
 *
 * 为什么不是纯 LRU：弹幕一段几百条、评论翻页一屏几十条，绝大多数只出现一次；纯 LRU 下这种"扫描"
 * 会把首页反复出现的推荐标题、热门评论等高频条目挤掉（落盘后还会带到下次启动）。
 *
 * 结构（容量 N）：
 * - 窗口段约 1%：新条目先进窗口，给"刚出现就马上再用"的条目一次机会；
 * - 主区 SLRU：考察段 20% + 保护段 80%；考察段命中即晋升保护段，保护段溢出降回考察段；
 * - 准入：窗口溢出的候选只有在估计频率**高于**主区淘汰受害者时才进主区，否则直接丢弃。
 * - 频率由 4 行 Count-Min Sketch（4 bit 饱和计数）估计，累计 10N 次记录后全体减半（老化），
 *   让过去的热点逐渐让位。
 *
 * 只存 128 位摘要键与屏蔽概率，不存原文。线程安全（整体 synchronized；条目少、操作 O(1)）。
 */
internal class SemanticScoreCache(
    private val maxEntries: Int = 3_000,
    /** 条目有效期；由设置页"判定结果保存时长"给出。 */
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    internal class Entry(val key: String, var score: Float, var storedAt: Long)

    private enum class Segment { WINDOW, PROBATION, PROTECTED }

    private val windowCap = (maxEntries / 100).coerceAtLeast(1)
    private val mainCap = (maxEntries - windowCap).coerceAtLeast(1)
    private val protectedCap = (mainCap * 8 / 10).coerceAtLeast(1)

    /** 三段各自按访问顺序排列：头部最久未用。 */
    private val window = LinkedHashMap<String, Entry>(windowCap * 2, 0.75f, true)
    private val probation = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val protectedSegment = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private val sketch = FrequencySketch(maxEntries)

    /** 自上次 [drainDirty] 以来新增或更新的条目数；硬盘存储据此决定何时落盘。 */
    private var dirty = 0

    /**
     * 未命中或过期返回 NaN。**只有命中计入频率**（写入另计一次）：未命中也计的话，一次性条目
     * "查一次 + 写一次"白得两次计数，扫描压力与老化速度都翻倍（与 Caffeine 的计数口径一致）。
     */
    @Synchronized
    fun get(key: String, now: Long): Float {
        val (segment, entry) = locate(key) ?: return Float.NaN
        sketch.increment(key)
        if (now - entry.storedAt > ttlMs) {
            remove(segment, key); return Float.NaN
        }
        // LinkedHashMap(accessOrder=true) 的 get 已把它移到队尾；考察段命中需晋升。
        if (segment == Segment.PROBATION) promote(key, entry)
        return entry.score
    }

    @Synchronized
    fun put(key: String, score: Float, now: Long) {
        sketch.increment(key)
        dirty += 1
        locate(key)?.let { (_, entry) ->
            entry.score = score
            entry.storedAt = now
            return
        }
        window[key] = Entry(key, score, now)
        if (window.size > windowCap) {
            val candidate = window.entries.iterator().let { it.next().value.also { _ -> it.remove() } }
            admit(candidate)
        }
    }

    /** 从硬盘恢复：跳过准入直接放进主区（文件本身就是上次存活下来的条目），保持原有时间戳。 */
    @Synchronized
    fun restore(entries: List<Entry>, now: Long) {
        entries.asSequence()
            .filter { now - it.storedAt <= ttlMs && locate(it.key) == null }
            .forEach { entry ->
                if (probation.size + protectedSegment.size < mainCap) probation[entry.key] = entry
                else if (window.size < windowCap) window[entry.key] = entry
            }
    }

    /** 当前全部未过期条目（硬盘存储用）；同时清零脏计数。 */
    @Synchronized
    fun snapshot(now: Long): List<Entry> {
        dirty = 0
        return (protectedSegment.values + probation.values + window.values)
            .filter { now - it.storedAt <= ttlMs }
            .map { Entry(it.key, it.score, it.storedAt) }
    }

    /** 作废一条（低可信来源的"屏蔽"待复核时用）；不存在时无操作。 */
    @Synchronized
    fun invalidate(key: String) {
        locate(key)?.let { (segment, _) ->
            remove(segment, key)
            dirty += 1
        }
    }

    @Synchronized
    fun dirtyCount(): Int = dirty

    @Synchronized
    fun size(): Int = window.size + probation.size + protectedSegment.size

    @Synchronized
    fun contains(key: String): Boolean = locate(key) != null

    private fun locate(key: String): Pair<Segment, Entry>? {
        protectedSegment[key]?.let { return Segment.PROTECTED to it }
        probation[key]?.let { return Segment.PROBATION to it }
        window[key]?.let { return Segment.WINDOW to it }
        return null
    }

    private fun remove(segment: Segment, key: String) {
        when (segment) {
            Segment.WINDOW -> window.remove(key)
            Segment.PROBATION -> probation.remove(key)
            Segment.PROTECTED -> protectedSegment.remove(key)
        }
    }

    private fun promote(key: String, entry: Entry) {
        probation.remove(key)
        protectedSegment[key] = entry
        if (protectedSegment.size > protectedCap) {
            val demoted = protectedSegment.entries.iterator().let { it.next().value.also { _ -> it.remove() } }
            probation[demoted.key] = demoted
        }
    }

    /** TinyLFU 准入：主区未满直接进考察段；满了则与受害者比频率，候选严格更高才替换。 */
    private fun admit(candidate: Entry) {
        if (probation.size + protectedSegment.size < mainCap) {
            probation[candidate.key] = candidate
            return
        }
        val victimMap = if (probation.isNotEmpty()) probation else protectedSegment
        val victim = victimMap.entries.first().value
        if (sketch.frequency(candidate.key) > sketch.frequency(victim.key)) {
            victimMap.remove(victim.key)
            probation[candidate.key] = candidate
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 4 行 Count-Min Sketch，4 bit 饱和计数（上限 15）；累计 [sampleSize] 次记录后全体减半。
 * 每行宽度取不小于 **4 × 容量** 的 2 的幂（与 Caffeine 的有效计数格密度同量级）：只取 1 × 容量时，
 * 一段弹幕这类大量一次性键会把计数器冲突噪声推到接近上限，热点与扫描条目的频率估计分不开
 * （`SemanticCacheTest` 的扫描用例即因此失败过）。容量 3000 时约 64 KB。
 */
internal class FrequencySketch(capacity: Int) {
    private val width = Integer.highestOneBit((capacity.coerceAtLeast(16) * 4 - 1) shl 1)
    private val mask = width - 1
    private val rows = Array(DEPTH) { ByteArray(width) }
    private val sampleSize = capacity.coerceAtLeast(16) * 10
    private var additions = 0

    fun increment(key: String) {
        val hash = spread(key.hashCode())
        var changed = false
        for (row in 0 until DEPTH) {
            val index = indexOf(hash, row)
            if (rows[row][index] < MAX) {
                rows[row][index] = (rows[row][index] + 1).toByte()
                changed = true
            }
        }
        if (changed && ++additions >= sampleSize) reset()
    }

    fun frequency(key: String): Int {
        val hash = spread(key.hashCode())
        var min = MAX.toInt()
        for (row in 0 until DEPTH) min = minOf(min, rows[row][indexOf(hash, row)].toInt())
        return min
    }

    private fun reset() {
        rows.forEach { row -> for (i in row.indices) row[i] = (row[i].toInt() ushr 1).toByte() }
        additions /= 2
    }

    private fun indexOf(hash: Int, row: Int): Int {
        var h = (hash + SEEDS[row]) * -0x61c88647
        h = h xor (h ushr 16)
        return h and mask
    }

    private fun spread(x: Int): Int {
        var h = x
        h = ((h ushr 16) xor h) * 0x45d9f3b
        h = ((h ushr 16) xor h) * 0x45d9f3b
        return (h ushr 16) xor h
    }

    private companion object {
        const val DEPTH = 4
        const val MAX: Byte = 15
        val SEEDS = intArrayOf(0x3c6ef372, -0x4ab325aa, 0x3c6ef373, -0x5b2dd4e7)
    }
}

/**
 * 判定结果的硬盘存储（宿主私有 `files/bil_semantic/<面>.bin`），每个过滤面一个文件。
 *
 * - 只存 128 位摘要、屏蔽概率与时间戳，每条 28 字节；3000 条约 84 KB。
 * - 文件头带"生成它的配置指纹"（模型 + 勾选规则 + 端点）：加载时对不上就整份丢弃，
 *   改勾选、换中转、换模型都不会沿用旧结论。
 * - 写入先写临时文件再改名，崩溃不会留下半截文件；读到损坏文件一律当作不存在。
 * - 读写都只在模块网络池线程上发生，宿主主线程不碰文件。
 */
internal class SemanticDiskStore(
    private val file: File,
    private val fingerprint: String
) {
    fun load(now: Long, ttlMs: Long): List<SemanticScoreCache.Entry> = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyList()
        DataInputStream(FileInputStream(file).buffered()).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return emptyList()
            if (input.readUTF() != fingerprint) return emptyList()
            val count = input.readInt()
            if (count !in 0..MAX_ENTRIES) return emptyList()
            val key = ByteArray(KEY_BYTES)
            List(count) {
                input.readFully(key)
                val score = input.readFloat()
                val storedAt = input.readLong()
                SemanticScoreCache.Entry(hex(key), score, storedAt)
            }.filter { it.score in 0f..1f && now - it.storedAt in 0..ttlMs }
        }
    }.getOrElse { emptyList() }

    fun save(entries: List<SemanticScoreCache.Entry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(fingerprint)
                val kept = entries.take(MAX_ENTRIES).filter { it.key.length == KEY_BYTES * 2 }
                output.writeInt(kept.size)
                kept.forEach { entry ->
                    output.write(unhex(entry.key))
                    output.writeFloat(entry.score)
                    output.writeLong(entry.storedAt)
                }
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun delete() {
        runCatching { file.delete() }
    }

    companion object {
        const val DIRECTORY = "bil_semantic"
        private const val MAGIC = 0x42534331 // "BSC1"
        private const val VERSION = 1
        private const val KEY_BYTES = 16
        private const val MAX_ENTRIES = 20_000
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val HEX = "0123456789abcdef"

        fun fileFor(root: File, surface: SemanticSurface): File =
            File(File(root, DIRECTORY), surface.name.lowercase() + ".bin")

        private fun hex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[i * 2] = HEX[v ushr 4]
                out[i * 2 + 1] = HEX[v and 0x0F]
            }
            return String(out)
        }

        private fun unhex(value: String): ByteArray =
            ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
