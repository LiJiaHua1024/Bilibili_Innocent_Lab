package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.io.File
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 按标题做智能过滤的列表面（首页推荐、相关推荐）共用的一段：取标题 → 选模式 → 判定 → 记日志。
 *
 * 模式：主线程只查缓存并投后台（绝不同步联网）；后台线程且开了"首屏等待"时同步等结果；
 * 否则投后台，下次加载生效。标题读不到的卡片为 UNKNOWN，按原有规则处理。
 * 单条入口（[verdictOf]）是例外，恒不阻塞——理由见那里。
 */
internal class SemanticTitleFilter(
    private val judge: SemanticJudge?,
    private val logDir: File?,
    private val isMainThread: () -> Boolean,
    private val source: String
) {
    val enabled: Boolean get() = judge != null
    private val memo = SemanticListMemo()

    fun verdicts(items: List<*>, titleOf: (Any) -> String?): IdentityHashMap<Any, SemanticVerdict>? {
        if (judge == null) return null
        return memo.getOrCompute(items) { compute(items, titleOf) }
    }

    /** 单条判定（组件工厂逐条回调）：每次都是新包装，绕过列表 memo，避免挤掉真正的列表条目。 */
    fun verdictOf(item: Any, titleOf: (Any) -> String?): SemanticVerdict =
        compute(listOf(item), titleOf, block = false)?.get(item) ?: SemanticVerdict.UNKNOWN

    /**
     * @param block 是否允许同步等判定。列表路径按设置决定；单条路径恒为 false——
     * 组件工厂是**逐条**回调的，一次只判一条，既合不进批、也没法预取：开着头屏等待时每张卡片都要
     * 串行阻塞一次网络往返，一屏二十张就是几十秒的卡顿（等待池只有 4 线程 12 队列，再多直接被拒）。
     * 改为只查缓存 + 投后台：标题此前经列表路径判过就直接命中，没判过的下次进页面生效。
     */
    private fun compute(
        items: List<*>,
        titleOf: (Any) -> String?,
        block: Boolean = true
    ): IdentityHashMap<Any, SemanticVerdict>? {
        val judge = judge ?: return null
        val present = items.filterNotNull()
        if (present.isEmpty()) return null
        val titles = present.map { item -> runCatching { titleOf(item) }.getOrNull()?.trim().orEmpty() }
        if (titles.all(String::isEmpty)) return null
        val mode = if (block && judge.waitFirstScreen && !isMainThread()) SemanticMode.WAIT else SemanticMode.PREFETCH
        val verdicts = judge.evaluate(titles, mode, onReport = logDir?.let { dir ->
            { report, batch, result -> log(dir, report, batch, result) }
        })
        return IdentityHashMap<Any, SemanticVerdict>(present.size).apply {
            present.forEachIndexed { index, item -> put(item, verdicts[index]) }
        }
    }

    private fun log(dir: File, report: SemanticBatchReport, texts: List<String>, verdicts: List<SemanticVerdict>) {
        val blocked = texts.indices.filter { verdicts[it] == SemanticVerdict.BLOCK }
            .joinToString(" | ") { texts[it].take(24) }
        SemanticDebugLog.append(
            dir,
            "${System.currentTimeMillis()} $source thread=${Thread.currentThread().name} total=${report.total} " +
                "requested=${report.requested} blocked=${report.blocked} ms=${report.elapsedMs} " +
                "outcome=${report.outcome}${report.extras()} :: $blocked"
        )
    }
}

/**
 * 列表 getter 可能在滚动/绑定时被反复调用：同一个列表实例（按引用）已全部判定完（没有 UNKNOWN）时直接复用，
 * 不再重复读正文、算摘要。仍有 UNKNOWN 的结果最多复用 [UNRESOLVED_REUSE_MS]，之后重算以接住后台判定的新结论。
 * 容量很小，只持有最近几个列表的强引用（它们本就被宿主持有、正在显示）。
 */
internal class SemanticListMemo(private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(
        val source: List<*>,
        val size: Int,
        val verdicts: IdentityHashMap<Any, SemanticVerdict>?,
        val resolved: Boolean,
        val at: Long
    )

    private val entries = ArrayDeque<Entry>(CAPACITY)

    fun getOrCompute(
        source: List<*>,
        compute: () -> IdentityHashMap<Any, SemanticVerdict>?
    ): IdentityHashMap<Any, SemanticVerdict>? {
        val now = clock()
        synchronized(entries) {
            // 同一引用且长度未变才复用：防宿主复用同一个可变列表换内容。
            entries.firstOrNull { it.source === source && it.size == source.size }?.let { hit ->
                if (hit.resolved || now - hit.at < UNRESOLVED_REUSE_MS) return hit.verdicts
                entries.remove(hit)
            }
        }
        val verdicts = compute()
        val resolved = verdicts != null && verdicts.values.none { it == SemanticVerdict.UNKNOWN }
        synchronized(entries) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(source, source.size, verdicts, resolved, now))
        }
        return verdicts
    }

    private companion object {
        const val CAPACITY = 6
        const val UNRESOLVED_REUSE_MS = 1_000L
    }
}

/**
 * 运行期定位卡片标题，**白名单**：卡片自身返回 String 的 `getTitle()`（`view.v1.Relate` 等），否则只认
 * `getBasicInfo().getTitle()`（`viewunite.common.RelateCard → CardBasicInfo`）。2026-09-29 对 28 个本地宿主
 * 静态核对三者全部存在。**不泛化下探**：`Relate` 的 `getButton()/getNotice()/getPackInfo()` 与
 * 更深处的 `RelateDislike` 也有 `getTitle()`，但都不是视频标题。按卡片类缓存路径；读不到返回 null。
 *
 * 不进 VersionAdapter：为一个可选的标题读取抬适配规则版本、让所有宿主全量重跑不值得。
 */
internal object SemanticTitleReader {
    private const val TITLE = "getTitle"
    private val CHILD_WHITELIST = listOf("getBasicInfo")
    private val paths = ConcurrentHashMap<Class<*>, List<List<Method>>>()

    fun read(item: Any): String? {
        for (path in paths.getOrPut(item.javaClass) { resolve(item.javaClass) }) {
            var target: Any? = item
            for (method in path) {
                target = target?.let { runCatching { method.invoke(it) }.getOrNull() }
            }
            (target as? String)?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        }
        return null
    }

    private fun titleGetter(type: Class<*>): Method? = runCatching { type.getMethod(TITLE) }.getOrNull()
        ?.takeIf { !it.isStatic && it.returnType == classOf<String>() }

    private fun resolve(type: Class<*>): List<List<Method>> {
        titleGetter(type)?.let { return listOf(listOf(it)) }
        return CHILD_WHITELIST.mapNotNull { name ->
            val child = runCatching { type.getMethod(name) }.getOrNull()
                ?.takeIf { !it.isStatic && it.parameterCount == 0 } ?: return@mapNotNull null
            titleGetter(child.returnType)?.let { listOf(child, it) }
        }
    }
}
