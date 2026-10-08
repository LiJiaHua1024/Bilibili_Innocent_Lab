package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 所有调用由 Runtime 的单个后台工作线程发起；只保存 Class/Member，不缓存宿主响应对象。 */
internal class HostAgentRpc(private val loader: ClassLoader) {
    val search by lazy { HostAgentSearchQuery.resolve(loader) }
    val details by lazy { HostAgentDetailQuery.resolve(loader) }
}

internal data class HostAgentVideo(
    val id: String,
    val title: String?,
    val author: String?,
    val mid: Long?,
    val duration: String?
) {
    fun json(): JSONObject = JSONObject().put("video_id", id)
        .put("title", title ?: JSONObject.NULL).put("author", author ?: JSONObject.NULL)
        .put("author_uid", mid?.toString() ?: JSONObject.NULL)
        .put("duration", duration ?: JSONObject.NULL).put("official_source", "unknown")
}

internal data class HostAgentSearchResult(val videos: List<HostAgentVideo>, val next: String?)

/** 30 个本地宿主都具备 CallOptions.withTimeout 与 Moss 的三参数构造，缺失时禁用主动 RPC。 */
internal class HostAgentMossFactory private constructor(
    private val optionsConstructor: Constructor<*>,
    private val withTimeout: Method,
    private val getTimeout: Method,
    private val mossConstructor: Constructor<*>
) {
    fun create(timeoutMs: Long): Any {
        val timeout = timeoutMs.coerceIn(1L, 12_000L)
        val options = checkNotNull(withTimeout.invoke(optionsConstructor.newInstance(), timeout, TimeUnit.MILLISECONDS))
        val actual = (getTimeout.invoke(options) as? Number)?.toLong()
        check(actual != null && actual in 1L..timeout) { "rpc_timeout_unavailable" }
        return mossConstructor.newInstance("grpc.biliapi.net", 443, options)
    }

    companion object {
        fun resolve(loader: ClassLoader, moss: Class<*>): HostAgentMossFactory? = runCatching {
            val options = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.moss.api.CallOptions") ?: return null
            HostAgentMossFactory(
                KavaMemberLookup.constructorOrNull(options) ?: return null,
                KavaMemberLookup.methodOrNull(options, "withTimeout", classOf<Long>(primitiveType = false), classOf<TimeUnit>())
                    ?.takeIf { !it.isStatic && it.returnType == options } ?: return null,
                hostGetter(options, "getTimeoutInMs") ?: return null,
                KavaMemberLookup.constructorOrNull(moss, classOf<String>(), classOf<Int>(), options)
                    ?: return null
            )
        }.getOrNull()
    }
}

internal class HostAgentSearchQuery private constructor(
    private val factory: HostAgentMossFactory,
    private val execute: Method,
    private val newBuilder: Method,
    private val setKeyword: Method,
    private val build: Method,
    private val itemList: Method,
    private val hasAv: Method,
    private val getAv: Method,
    private val getParam: Method?,
    private val getUri: Method?,
    private val getTitle: Method?,
    private val getAuthor: Method?,
    private val getMid: Method?,
    private val getDuration: Method?,
    private val pagination: Pagination?
) {
    val canPaginate: Boolean get() = pagination != null

    fun query(keyword: String, cursor: String?, timeoutMs: Long): HostAgentSearchResult {
        val builder = checkNotNull(newBuilder.invoke(null))
        setKeyword.invoke(builder, keyword)
        if (cursor != null) check(pagination != null) { "pagination_unavailable" }
        pagination?.let { plan ->
            val page = checkNotNull(plan.newBuilder.invoke(null))
            plan.setPageSize.invoke(page, 20)
            if (cursor != null) plan.setNext.invoke(page, cursor)
            plan.setPagination.invoke(builder, plan.build.invoke(page))
        }
        val reply = checkNotNull(execute.invoke(factory.create(timeoutMs), build.invoke(builder)))
        val items = itemList.invoke(reply) as? List<*> ?: error("search_result_unavailable")
        val videos = items.asSequence().take(100).filterNotNull().mapNotNull { item ->
            if (hostRead(hasAv, item) != true) return@mapNotNull null
            val card = hostRead(getAv, item) ?: return@mapNotNull null
            val id = HostAgentNavigationPolicy.candidateId(
                hostRead(getParam, item) as? String, hostRead(getUri, item) as? String
            ) ?: return@mapNotNull null
            HostAgentVideo(
                id, hostText(hostRead(getTitle, card), 300), hostText(hostRead(getAuthor, card), 100),
                (hostRead(getMid, card) as? Number)?.toLong()?.takeIf { it > 0 },
                hostText(hostRead(getDuration, card), 40)
            )
        }.distinctBy { it.id }.take(20).toList()
        val next = pagination?.let { plan ->
            hostRead(plan.getPagination, reply)?.let { hostRead(plan.getNext, it) as? String }
        }?.takeIf { it.isNotBlank() && it.length <= 2048 }
        return HostAgentSearchResult(videos, next)
    }

    private data class Pagination(
        val newBuilder: Method, val setPageSize: Method, val setNext: Method, val build: Method,
        val setPagination: Method, val getPagination: Method, val getNext: Method
    )

    companion object {
        private const val BASE = "com.bapis.bilibili.polymer.app.search.v1."
        fun resolve(loader: ClassLoader): HostAgentSearchQuery? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, BASE + "SearchMoss") ?: return null
            val request = KavaMemberLookup.classOrNull(loader, BASE + "SearchAllRequest") ?: return null
            val response = KavaMemberLookup.classOrNull(loader, BASE + "SearchAllResponse") ?: return null
            val item = KavaMemberLookup.classOrNull(loader, BASE + "Item") ?: return null
            val newBuilder = hostBuilder(request) ?: return null
            val builder = newBuilder.returnType
            val av = hostGetter(item, "getAv")?.takeIf { it.returnType.name == BASE + "SearchVideoCard" } ?: return null
            val card = av.returnType
            val page = runCatching {
                val getter = hostGetter(response, "getPagination") ?: return@runCatching null
                val pageType = KavaMemberLookup.classOrNull(loader, "com.bapis.bilibili.pagination.Pagination")
                    ?: return@runCatching null
                val pageBuilder = hostBuilder(pageType) ?: return@runCatching null
                Pagination(pageBuilder,
                    KavaMemberLookup.methodOrNull(pageBuilder.returnType, "setPageSize", classOf<Int>())
                        ?: return@runCatching null,
                    KavaMemberLookup.methodOrNull(pageBuilder.returnType, "setNext", classOf<String>())
                        ?: return@runCatching null,
                    hostBuild(pageBuilder.returnType) ?: return@runCatching null,
                    KavaMemberLookup.methodOrNull(builder, "setPagination", pageType) ?: return@runCatching null,
                    getter, hostGetter(getter.returnType, "getNext", classOf<String>()) ?: return@runCatching null)
            }.getOrNull()
            HostAgentSearchQuery(
                HostAgentMossFactory.resolve(loader, moss) ?: return null,
                KavaMemberLookup.methodOrNull(moss, "executeSearchAll", request)
                    ?.takeIf { !it.isStatic && it.returnType == response } ?: return null,
                newBuilder, KavaMemberLookup.methodOrNull(builder, "setKeyword", classOf<String>()) ?: return null,
                hostBuild(builder) ?: return null,
                hostGetter(response, "getItemList")?.takeIf { it.returnType isSubclassOf classOf<List<*>>() } ?: return null,
                hostGetter(item, "hasAv", classOf<Boolean>()) ?: return null,
                av, hostGetter(item, "getParam", classOf<String>()), hostGetter(item, "getUri", classOf<String>()),
                hostGetter(card, "getTitle", classOf<String>()), hostGetter(card, "getAuthor", classOf<String>()),
                hostGetter(card, "getMid", classOf<Long>()), hostGetter(card, "getDuration"), page
            )
        }.getOrNull()
    }
}

internal class HostAgentDetailQuery private constructor(
    private val factory: HostAgentMossFactory,
    private val execute: Method,
    private val newBuilder: Method,
    private val setAid: Method,
    private val setBvid: Method?,
    private val setSpmid: Method,
    private val build: Method,
    private val hasArc: Method,
    private val getArc: Method,
    private val getAid: Method,
    private val getBvid: Method?,
    private val getTitle: Method?,
    private val getDuration: Method?,
    private val hasOwner: Method?,
    private val getOwner: Method?,
    private val getOwnerMid: Method?,
    private val getOwnerName: Method?,
    private val hasVerify: Method?,
    private val getVerify: Method?,
    private val getVerifyType: Method?,
    private val getVerifyDesc: Method?,
    private val description: Description?
) {
    val canVerifyOwner: Boolean get() = hasVerify != null && getVerifyType != null && getVerifyDesc != null

    fun query(id: String, timeoutMs: Long): JSONObject {
        val builder = checkNotNull(newBuilder.invoke(null))
        if (id.startsWith("av")) setAid.invoke(builder, id.removePrefix("av").toLong())
        else checkNotNull(setBvid) { "bvid_query_unavailable" }.invoke(builder, id)
        // 与现有 AiViewQuery 使用相同的被动查询 spmid，避免被详情净化器当成用户打开视频。
        setSpmid.invoke(builder, "main.my-history.recommend.0")
        val reply = checkNotNull(execute.invoke(factory.create(timeoutMs), build.invoke(builder)))
        check(hostRead(hasArc, reply) == true) { "video_details_unavailable" }
        val arc = checkNotNull(hostRead(getArc, reply))
        val actualAid = (hostRead(getAid, arc) as? Number)?.toLong()?.takeIf { it > 0 }?.let { "av$it" }
        val actualBvid = HostAgentNavigationPolicy.videoId(hostRead(getBvid, arc) as? String)
        check(id == actualAid || id == actualBvid) { "video_identity_mismatch" }
        val owner = if (hostRead(hasOwner, reply) == true) hostRead(getOwner, reply) else null
        val verify = if (hostRead(hasVerify, owner) == true) hostRead(getVerify, owner) else null
        val verification = if (verify != null && canVerifyOwner) JSONObject()
            .put("status", "reported").put("type", hostRead(getVerifyType, verify) ?: JSONObject.NULL)
            .put("description", hostText(hostRead(getVerifyDesc, verify), 300) ?: JSONObject.NULL)
        else JSONObject().put("status", "unknown")
        return JSONObject().put("video_id", id).put("source", "host_rpc")
            .put("title", hostText(hostRead(getTitle, arc), 300) ?: JSONObject.NULL)
            .put("description", description?.read(reply) ?: JSONObject.NULL)
            .put("duration_seconds", (hostRead(getDuration, arc) as? Number)?.toLong()?.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("author_uid", (hostRead(getOwnerMid, owner) as? Number)?.toLong()?.takeIf { it > 0 }?.toString() ?: JSONObject.NULL)
            .put("author", hostText(hostRead(getOwnerName, owner), 100) ?: JSONObject.NULL)
            .put("verification", verification).put("official_source", "unknown")
    }

    private class Description(
        val hasTab: Method, val tab: Method, val tabs: Method, val hasIntro: Method, val intro: Method,
        val modules: Method, val hasUgc: Method, val ugc: Method, val desc: Method, val info: Method
    ) {
        fun read(reply: Any): String? {
            if (hostRead(hasTab, reply) != true) return null
            val tabItems = hostRead(tabs, hostRead(tab, reply)) as? List<*> ?: return null
            val parts = mutableListOf<String>()
            var size = 0
            for (tabItem in tabItems.take(8)) {
                if (hostRead(hasIntro, tabItem) != true) continue
                val items = hostRead(modules, hostRead(intro, tabItem)) as? List<*> ?: continue
                for (item in items.take(24)) {
                    if (hostRead(hasUgc, item) != true) continue
                    val descriptions = hostRead(desc, hostRead(ugc, item)) as? List<*> ?: continue
                    for (entry in descriptions.take(16)) {
                        val part = hostText(hostRead(info, entry), 2000 - size) ?: continue
                        parts += part
                        size += part.length + 1
                        if (size >= 2000) return parts.joinToString("\n").take(2000)
                    }
                }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    companion object {
        private const val BASE = "com.bapis.bilibili.app.viewunite.v1."
        fun resolve(loader: ClassLoader): HostAgentDetailQuery? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, BASE + "ViewMoss") ?: return null
            val request = KavaMemberLookup.classOrNull(loader, BASE + "ViewReq") ?: return null
            val response = KavaMemberLookup.classOrNull(loader, BASE + "ViewReply") ?: return null
            val newBuilder = hostBuilder(request) ?: return null
            val builder = newBuilder.returnType
            val arc = hostGetter(response, "getArc") ?: return null
            val owner = hostGetter(response, "getOwner")
            val verify = owner?.returnType?.let { hostGetter(it, "getOfficialVerify") }
            val description = runCatching {
                val tab = hostGetter(response, "getTab") ?: return@runCatching null
                val tabClass = KavaMemberLookup.classOrNull(loader, BASE + "TabModule") ?: return@runCatching null
                val intro = hostGetter(tabClass, "getIntroduction") ?: return@runCatching null
                val common = "com.bapis.bilibili.app.viewunite.common."
                val module = KavaMemberLookup.classOrNull(loader, common + "Module") ?: return@runCatching null
                val ugc = hostGetter(module, "getUgcIntroduction") ?: return@runCatching null
                val desc = KavaMemberLookup.classOrNull(loader, common + "Desc") ?: return@runCatching null
                Description(hostGetter(response, "hasTab") ?: return@runCatching null, tab,
                    hostGetter(tab.returnType, "getTabModuleList") ?: return@runCatching null,
                    hostGetter(tabClass, "hasIntroduction") ?: return@runCatching null, intro,
                    hostGetter(intro.returnType, "getModulesList") ?: return@runCatching null,
                    hostGetter(module, "hasUgcIntroduction") ?: return@runCatching null, ugc,
                    hostGetter(ugc.returnType, "getDescList") ?: return@runCatching null,
                    hostGetter(desc, "getInfo", classOf<String>()) ?: return@runCatching null)
            }.getOrNull()
            HostAgentDetailQuery(
                HostAgentMossFactory.resolve(loader, moss) ?: return null,
                KavaMemberLookup.methodOrNull(moss, "executeView", request)
                    ?.takeIf { !it.isStatic && it.returnType == response } ?: return null,
                newBuilder, KavaMemberLookup.methodOrNull(builder, "setAid", classOf<Long>()) ?: return null,
                KavaMemberLookup.methodOrNull(builder, "setBvid", classOf<String>()),
                KavaMemberLookup.methodOrNull(builder, "setSpmid", classOf<String>()) ?: return null,
                hostBuild(builder) ?: return null, hostGetter(response, "hasArc") ?: return null, arc,
                hostGetter(arc.returnType, "getAid") ?: return null, hostGetter(arc.returnType, "getBvid", classOf<String>()),
                hostGetter(arc.returnType, "getTitle", classOf<String>()), hostGetter(arc.returnType, "getDuration"),
                hostGetter(response, "hasOwner"), owner,
                owner?.returnType?.let { hostGetter(it, "getMid") }, owner?.returnType?.let { hostGetter(it, "getTitle", classOf<String>()) },
                owner?.returnType?.let { hostGetter(it, "hasOfficialVerify") }, verify,
                verify?.returnType?.let { hostGetter(it, "getType", classOf<Int>()) },
                verify?.returnType?.let { hostGetter(it, "getDesc", classOf<String>()) }, description
            )
        }.getOrNull()
    }
}

private fun hostGetter(owner: Class<*>, name: String, type: Class<*>? = null): Method? =
    KavaMemberLookup.methodOrNull(owner, name)?.takeIf {
        !it.isStatic && it.parameterCount == 0 && (type == null || it.returnType == type)
    }

private fun hostBuilder(owner: Class<*>): Method? = KavaMemberLookup.methodOrNull(owner, "newBuilder")?.takeIf {
    it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive
}

private fun hostBuild(owner: Class<*>): Method? = KavaMemberLookup.inheritedMethodOrNull(owner, "build")?.takeIf {
    !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive
}

private fun hostRead(method: Method?, target: Any?): Any? =
    if (method == null || target == null || !method.declaringClass.isInstance(target)) null
    else runCatching { method.invoke(target) }.getOrNull()

private fun hostText(raw: Any?, limit: Int): String? = (raw as? String)?.take(limit.coerceAtLeast(0))
    ?.replace(Regex("</?em>"), "")?.filterNot { it.isISOControl() && it != '\n' }?.takeIf { it.isNotBlank() }
