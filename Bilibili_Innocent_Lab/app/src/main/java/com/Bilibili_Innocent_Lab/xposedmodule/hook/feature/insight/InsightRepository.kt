package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 只存生成结果，不持久化原始字幕、评论、问题或凭据。按视频+分 P+模型+协议隔离。 */
internal class InsightCache(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val output: InsightOutput, val duration: Int, val timed: Boolean)
    private val memory = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 32
    }
    @Synchronized fun read(key: String, mode: InsightMode): Entry? {
        // 内存命中也检查落盘时间，评论不会在长驻进程里永久过期不了。
        val file = file(key)
        if (!file.isFile || file.length() > 80_000 || clock() - file.lastModified() !in 0..ttl(mode)) {
            memory.remove(key); return null
        }
        memory[key]?.let { return it }
        return runCatching {
            val root = JSONObject(file.readText())
            require(root.getString("key") == key && root.getInt("version") == InsightProtocol.VERSION)
            val chapters = root.getJSONArray("chapters")
            require(chapters.length() <= 12)
            Entry(InsightOutput(root.getString("text").also { require(it.length <= InsightProtocol.MAX_OUTPUT) },
                (0 until chapters.length()).map { i -> chapters.getJSONObject(i).let {
                    InsightChapter(it.getInt("start"), it.getString("title")) } }),
                root.getInt("duration"), root.getBoolean("timed")).also { memory[key] = it }
        }.getOrNull()
    }
    @Synchronized fun write(key: String, entry: Entry) {
        require(entry.output.text.length <= InsightProtocol.MAX_OUTPUT && entry.output.chapters.size <= 12)
        check(directory.isDirectory || directory.mkdirs())
        val json = JSONObject().put("key", key).put("version", InsightProtocol.VERSION)
            .put("text", entry.output.text).put("duration", entry.duration).put("timed", entry.timed)
            .put("chapters", JSONArray(entry.output.chapters.map { JSONObject().put("start", it.start).put("title", it.title) }))
        val target = file(key)
        val temporary = File.createTempFile("insight-", ".tmp", directory)
        try {
            temporary.writeText(json.toString())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(clock())
            memory[key] = entry
        } finally { temporary.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(200)?.forEach { it.delete() }
    }
    fun restoreChapters(source: AgentModelSource, accept: (InsightVideo, Entry) -> Unit) {
        directory.listFiles()?.filter { it.extension == "json" && it.length() <= 80_000 }?.take(200)?.forEach { file ->
            runCatching {
                val key = JSONObject(file.readText()).getString("key")
                val parts = key.split('/'); if (parts.size < 4 || parts[2] != InsightMode.CHAPTERS.name) return@runCatching
                val videoPart = parts[1].split('-'); if (videoPart.size != 2) return@runCatching
                val video = InsightVideo(videoPart[0], videoPart[1].toLong())
                if (key == InsightCache.key(video, source, InsightMode.CHAPTERS)) read(key, InsightMode.CHAPTERS)?.let { accept(video, it) }
            }
        }
    }
    private fun file(key: String) = File(directory, "${digest(key)}.json")
    private fun ttl(mode: InsightMode) = if (mode == InsightMode.COMMENTS) 60 * 60_000L else 90 * 24 * 60 * 60_000L
    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
        fun key(video: InsightVideo, source: AgentModelSource, mode: InsightMode): String =
            "${InsightProtocol.VERSION}/${video.key}/${mode.name}/${digest(source.resolvedEndpoint + '\u0000' + source.model)}"
    }
}

/** 调用方使用一个有界后台队列；缓存先于上下文加载，二次打开不请求 B 站或模型。 */
internal class InsightRepository(
    private val source: AgentModelSource, private val cache: InsightCache,
    private val context: (InsightVideo) -> InsightContext,
    private val client: AgentModelClient = AgentModelClient(outputTokenBudget = 4096)
) {
    private var loaded: InsightContext? = null
    private var loadedAt = 0L
    // 缓存读取不拿生成锁；慢模型请求不能阻挡另一个视频的缓存命中。
    fun cached(video: InsightVideo, mode: InsightMode): InsightCache.Entry? =
        if (mode == InsightMode.ASK) null else cache.read(InsightCache.key(video, source, mode), mode)
    @Synchronized fun generate(video: InsightVideo, mode: InsightMode, question: String = "",
        history: List<Pair<String, String>> = emptyList(), cancelled: () -> Boolean = { false }): InsightCache.Entry {
        check(!cancelled())
        val key = InsightCache.key(video, source, mode)
        if (mode != InsightMode.ASK) cache.read(key, mode)?.let { return it }
        val now = System.currentTimeMillis()
        val input = loaded?.takeIf { it.video == video && now - loadedAt in 0..300_000L }
            ?: context(video).also { loaded = it; loadedAt = now }
        check(!cancelled())
        if (mode == InsightMode.COMMENTS) require(input.comments.isNotEmpty()) { "no-comments" }
        if (mode == InsightMode.CHAPTERS) require(input.timed && !input.officialChapters && input.duration >= 600) { "chapters-unavailable" }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", InsightProtocol.system(mode, input)))
            .put(JSONObject().put("role", "user").put("content", InsightProtocol.prompt(mode, input, question, history)))
        val turn = client.generateStructured(source, messages, InsightProtocol.schema(mode, input), 60_000, cancelled)
        check(!cancelled())
        val result = InsightCache.Entry(InsightProtocol.decode(mode, turn.text, input), input.duration, input.timed)
        if (mode != InsightMode.ASK) cache.write(key, result)
        return result
    }
}
