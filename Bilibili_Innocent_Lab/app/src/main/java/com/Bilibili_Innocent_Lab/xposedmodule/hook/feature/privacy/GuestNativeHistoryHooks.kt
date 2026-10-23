package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf

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
        val moss = host.type(PREFIX + "HistoryMoss")
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        val next = requireNotNull(Lookup.methodOrNull(handler, "onNext", classOf<Any>()))
        val completed = requireNotNull(Lookup.methodOrNull(handler, "onCompleted"))
        val exception = host.type("com.bilibili.lib.moss.api.MossException")
        val error = requireNotNull(Lookup.methodOrNull(handler, "onError", exception))
        val errorConstructor = requireNotNull(Lookup.constructorOrNull(exception, classOf<String>(), classOf<Throwable>()))
        for ((name, requestName) in mapOf("historyTab" to "HistoryTabReq", "historyTabV2" to "HistoryTabReq",
            "cursorV2" to "CursorV2Req", "search" to "SearchReq", "clear" to "ClearReq", "delete" to "DeleteReq")) {
            env.registrar.exact("guest.native.history.$name", moss, name, host.type(PREFIX + requestName), handler) {
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
        val fragment = host.type("com.bilibili.app.history.HistoryFragmentV3")
        env.registrar.exact("guest.native.history.presentation", fragment, "isLogin") {
            before { if (isGuest()) result = true }
        }
        env.registrar.exact("guest.native.history.login_banner", fragment, "up", classOf<Boolean>()) {
            before { if (isGuest()) args[0] = false }
        }
        for ((name, getter) in mapOf("com.bilibili.app.history.ui.viewmodel.HistoryContentViewModel" to "I2",
            "com.bilibili.app.history.search.presenter.HistorySearchTabViewModel" to "u2")) {
            env.registrar.exact("guest.native.history.cache.$getter", host.type(name), getter) {
                before { if (isGuest()) result = null }
            }
        }
        env.reportStatus("guest_native_history", "adapted")
    }
    companion object { private const val PREFIX = "com.bapis.bilibili.app.interfaces.v1." }
}
