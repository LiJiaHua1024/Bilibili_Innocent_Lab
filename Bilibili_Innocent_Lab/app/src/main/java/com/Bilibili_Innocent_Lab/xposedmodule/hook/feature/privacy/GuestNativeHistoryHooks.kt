package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic

/** Host 8.90.2 history cards, tabs, search and editing; no host history database or remote RPC. */
internal class GuestNativeHistoryHooks(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        fun proto(name: String, fields: Map<String, Any> = emptyMap()): Any {
            val builder = requireNotNull(host.static(host.type(PREFIX + name), "newBuilder"))
            fields.forEach { (key, value) -> host.call(builder, key, value) }
            return requireNotNull(host.call(builder, "build"))
        }
        fun tab(name: String, business: String, search: Boolean) = proto("CursorTab", mapOf(
            "setName" to name, "setBusiness" to business, "setFocus" to (business == "all"),
            "setRouter" to "bilibili://main/history/${if (search) "search/" else ""}${if (business == "archive") "video" else business}"))
        fun card(video: LocalVirtualLibrary.Video): Any = proto("CursorItem", mapOf(
            "setBusiness" to "archive", "setKid" to video.aid, "setOid" to video.aid,
            "setTitle" to video.title, "setViewAt" to video.updatedAtMs / 1000,
            "setUri" to "bilibili://video/${video.aid}?cid=${video.cid}", "setTp" to 3,
            "setCardUgc" to proto("CardUGC", mapOf("setCid" to video.cid,
                "setCover" to video.cover.orEmpty(), "setDuration" to video.durationMs / 1000,
                "setProgress" to if (video.isComplete) -1L else video.positionMs / 1000,
                "setName" to video.authorName, "setMid" to video.authorMid, "setVideos" to 1L, "setPage" to 1))))
        fun response(name: String, request: Any, data: LocalVirtualLibrary): Any {
            if (name == "historyTab" || name == "historyTabV2") {
                val search = host.call(request, "getSource").toString().contains("search", true)
                return proto("HistoryTabReply", mapOf("addAllTab" to listOf(tab("全部", "all", search),
                    tab("视频", "archive", search), tab("未看完", "nonfinish", search))))
            }
            val snapshot = data.readSnapshot()
            if (name == "clear" || name == "delete") {
                val ids = if (name == "delete") (host.call(request, "getHisInfoList") as List<*>).map {
                    (host.call(requireNotNull(it), "getKid") as Number).toLong()
                }.toSet() else null
                val business = if (name == "clear") host.call(request, "getBusiness") as String else ""
                val remaining = snapshot.collections.history.filterNot {
                    (ids == null || it.aid in ids) && (business != "nonfinish" || !it.isComplete)
                }
                data.writeSnapshot(snapshot.copy(collections = snapshot.collections.copy(history = remaining)))
                return proto("NoReply")
            }
            val business = host.call(request, "getBusiness") as String
            var videos = snapshot.collections.history.filter { business != "nonfinish" || !it.isComplete }
            if (business !in setOf("", "all", "archive", "video", "nonfinish")) videos = emptyList()
            if (name == "search") {
                val keyword = host.call(request, "getKeyword") as String
                videos = videos.filter { it.title.contains(keyword, true) || it.authorName.contains(keyword, true) }
                val pn = (host.call(request, "getPn") as Number).toLong().coerceAtLeast(1)
                return proto("SearchReply", mapOf("addAllItems" to if (pn == 1L) videos.map(::card) else emptyList(),
                    "setHasMore" to false, "setPage" to proto("Page", mapOf("setPn" to pn, "setTotal" to videos.size.toLong()))))
            }
            val cursor = requireNotNull(host.call(request, "getCursor"))
            if ((host.call(cursor, "getMax") as Number).toLong() != 0L) videos = emptyList()
            return proto("CursorV2Reply", mapOf("addAllItems" to videos.map(::card), "setHasMore" to false,
                "setCursor" to proto("Cursor")))
        }
        // Preflight: every RPC/callback anchor is resolved before any hook exists. HistoryMoss and its
        // request/reply protobufs are stable gRPC names on all 38 hostlab builds (8.84-9.17).
        val moss = host.type(PREFIX + "HistoryMoss")
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        val next = requireNotNull(Lookup.methodOrNull(handler, "onNext", classOf<Any>()))
        val completed = requireNotNull(Lookup.methodOrNull(handler, "onCompleted"))
        val exception = host.type("com.bilibili.lib.moss.api.MossException")
        val error = requireNotNull(Lookup.methodOrNull(handler, "onError", exception))
        val errorConstructor = requireNotNull(Lookup.constructorOrNull(exception, classOf<String>(), classOf<Throwable>()))
        val rpcs = mapOf("historyTab" to "HistoryTabReq", "historyTabV2" to "HistoryTabReq",
            "cursorV2" to "CursorV2Req", "search" to "SearchReq", "clear" to "ClearReq", "delete" to "DeleteReq")
            .mapValues { (name, request) ->
                val type = host.type(PREFIX + request)
                requireNotNull(Lookup.methodOrNull(moss, name, type, handler)) { "HistoryMoss.$name" }
                type
            }
        val plan = PrivacyInstallPlan()
        for ((name, requestType) in rpcs) plan.step {
            env.registrar.exact("guest.native.history.$name", moss, name, requestType, handler) {
                before {
                    if (!isGuest()) return@before
                    result = null
                    val request = requireNotNull(argOrNull(0))
                    val callback = requireNotNull(argOrNull(1))
                    library.nativeRequest({ response(name, request, it) }) { outcome ->
                        outcome.fold({ value -> next.invoke(callback, value); completed.invoke(callback) },
                            { error.invoke(callback, errorConstructor.newInstance("Local history unavailable", it)) })
                    }
                }
            }
        }
        // Presentation is version-specific and optional: 8.84-8.96 expose a fragment isLogin() gate,
        // a few builds the up(boolean) login banner and fallback-cache getters. 8.97+ inline these into
        // BiliAccounts calls, so the page still renders local data but keeps the host's guest chrome.
        val presentation = mutableListOf<String>()
        val fragment = Lookup.classOrNull(host.loader, "com.bilibili.app.history.HistoryFragmentV3")
        fragment?.let { Lookup.methodOrNull(it, "isLogin") }
            ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() }?.let { method ->
                presentation += "isLogin"
                plan.step {
                    env.registrar.exact("guest.native.history.presentation", method.declaringClass, method.name) {
                        before { if (isGuest()) result = true }
                    }
                }
            }
        fragment?.let { Lookup.methodOrNull(it, "up", classOf<Boolean>()) }
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE }?.let { method ->
                presentation += "banner"
                plan.step {
                    env.registrar.exact("guest.native.history.login_banner", method.declaringClass, method.name, classOf<Boolean>()) {
                        before { if (isGuest()) args[0] = false }
                    }
                }
            }
        for ((name, getter) in mapOf("com.bilibili.app.history.ui.viewmodel.HistoryContentViewModel" to "I2",
            "com.bilibili.app.history.search.presenter.HistorySearchTabViewModel" to "u2")) {
            val method = Lookup.classOrNull(host.loader, name)?.let { Lookup.methodOrNull(it, getter) }
                ?.takeIf { !it.isStatic && it.returnType.name.startsWith("com.bilibili.common.fallbackcache.") }
                ?: continue
            presentation += "cache.$getter"
            plan.step {
                env.registrar.exact("guest.native.history.cache.$getter", method.declaringClass, method.name) {
                    before { if (isGuest()) result = null }
                }
            }
        }
        plan.apply()
        env.reportStatus("guest_native_history",
            if (presentation.isEmpty()) "adapted-rpc" else "adapted:${presentation.joinToString("+")}")
    }
    companion object { private const val PREFIX = "com.bapis.bilibili.app.interfaces.v1." }
}
