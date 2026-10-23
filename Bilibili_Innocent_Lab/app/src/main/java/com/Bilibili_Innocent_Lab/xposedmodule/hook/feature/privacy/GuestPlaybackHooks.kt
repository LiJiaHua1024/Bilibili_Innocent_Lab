package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.classOf
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Metadata never selects the current video; only a bound, advancing player can create history. */
internal class GuestPlaybackHooks(
    private val library: GuestLibraryRuntime,
    private val guest: () -> Boolean
) {
    private data class Playback(val aid: Long, val cid: Long, var last: Long = -1,
        var restored: Boolean = false, var resume: Long? = null)
    private val players = WeakHashMap<Any, Playback>()
    private val main = Handler(Looper.getMainLooper())

    fun install(env: HookEnvironment): Int {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        var count = 0
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        fun metadata(reply: Any) {
            if (!guest()) return
            runCatching {
                library.recordVideo(PrivacyVideoMetadata.read(host, reply))
            }.onFailure { env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR) }
        }
        for (prefix in listOf("com.bapis.bilibili.app.viewunite.v1", "com.bapis.bilibili.app.view.v1")) {
            val moss = Lookup.classOrNull(host.loader, "$prefix.ViewMoss") ?: continue
            val req = Lookup.classOrNull(host.loader, "$prefix.ViewReq") ?: continue
            if (Lookup.methodOrNull(moss, "executeView", req) != null) {
                env.registrar.exact("guest.metadata.$prefix.sync", moss, "executeView", req) {
                    after { if (!hasThrowable) result?.let(::metadata) }
                }
                count++
            }
            if (Lookup.methodOrNull(moss, "view", req, handler) != null) {
                env.registrar.exact("guest.metadata.$prefix.async", moss, "view", req, handler) {
                    before {
                        if (guest()) argOrNull(1)?.let {
                            args[1] = MossResponseHandlerProxy.wrap(handler, it, ::metadata) ?: it
                        }
                    }
                }
                count++
            }
        }
        val resource = host.type("com.bilibili.lib.media.resource.MediaResource")
        val params = host.type("tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams")
        val item = host.type("tv.danmaku.videoplayer.coreV2.MediaItem")
        val coreNames = listOf("lf6.p0",
            "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1")
        for (coreName in coreNames) {
            val core = Lookup.classOrNull(host.loader, coreName) ?: continue
            for (types in listOf(arrayOf(resource, classOf<Boolean>(), params),
                arrayOf(item, resource, classOf<Boolean>(), params))) {
                if (Lookup.methodOrNull(core, "setMediaResource", *types) == null) continue
                env.registrar.exact("guest.player.bind.$coreName.${types.size}", core, "setMediaResource", *types) {
                    before {
                        if (!guest()) return@before
                        val player = instance ?: return@before
                        val p = argOrNull(types.lastIndex) ?: return@before
                        val aid = (host.call(p, "getAvid") as Number).toLong()
                        // MediaItemParams.id is the media cid, validated against numeric UGC ids.
                        val cid = (host.call(p, "getId") as? String)?.toLongOrNull() ?: return@before
                        if (aid <= 0 || cid <= 0) return@before
                        val state = synchronized(players) {
                            players[player]?.takeIf { it.aid == aid && it.cid == cid }
                                ?: Playback(aid, cid).also { players[player] = it }
                        }
                        val weak = WeakReference(player)
                        library.resumePosition(aid, cid) { position ->
                            weak.get()?.let { current ->
                                synchronized(players) {
                                    if (players[current] === state && !state.restored) state.resume = position
                                }
                            }
                        }
                    }
                }
                count++
            }
            if (Lookup.methodOrNull(core, "getCurrentPosition") != null) {
                env.registrar.exact("guest.player.progress.$coreName", core, "getCurrentPosition") {
                    after {
                        if (!guest() || hasThrowable) return@after
                        val player = instance ?: return@after
                        val position = (result as? Int)?.toLong() ?: return@after
                        val state = synchronized(players) { players[player] } ?: return@after
                        if (position <= 0) return@after
                        val duration = (host.call(player, "getDuration") as Number).toLong()
                        if (duration <= 0) return@after
                        val resume = synchronized(players) {
                            val target = state.resume
                            if (!state.restored && target != null) {
                                state.restored = true
                                if (position <= 2000 && target > 0 && target < duration) target else null
                            } else null
                        }
                        if (resume != null) {
                            val weak = WeakReference(player)
                            main.post {
                                weak.get()?.let { current ->
                                    if (synchronized(players) { players[current] === state } && guest())
                                        runCatching { host.call(current, "seekTo", resume.toInt()) }
                                }
                            }
                            return@after
                        }
                        val write = synchronized(players) {
                            if (state.last == position || state.last >= 0 && kotlin.math.abs(position - state.last) < 1000) false
                            else { state.last = position; true }
                        }
                        if (write) library.recordProgress(state.aid, state.cid, position, duration)
                    }
                }
                count++
            }
            // Flush the final millisecond value before pause/release; hot progress writes are throttled.
            for (name in listOf("pause", "stop", "onDestroy")) {
                if (Lookup.methodOrNull(core, name) == null) continue
                env.registrar.exact("guest.player.flush.$coreName.$name", core, name) {
                    before {
                        if (!guest()) return@before
                        val player = instance ?: return@before
                        val state = synchronized(players) { players[player] } ?: return@before
                        val position = (host.call(player, "getRealCurrentPosition") as Number).toLong()
                        val duration = (host.call(player, "getDuration") as Number).toLong()
                        if (position > 0 && duration > 0) library.recordProgress(state.aid, state.cid, position, duration)
                    }
                }
                count++
            }
        }

        // The modern player writes its DB and reports to the server from the same confirmed-playback event.
        val history = host.type("com.bilibili.ship.theseus.united.player.history.TheseusHistoryService")
        val historyState = host.type("com.bilibili.ship.theseus.united.player.history.TheseusHistoryService\$b")
        val playable = requireNotNull(Lookup.fieldOrNull(history, "g"))
        val millis = requireNotNull(Lookup.methodOrNull(host.type("kotlin.time.Duration"),
            "getInWholeMilliseconds-impl", classOf<Long>()))
        env.registrar.exact("guest.player.theseus_history", history, "t", historyState, classOf<Long>()) {
            before {
                if (!guest()) return@before
                // Block before reading metadata; adapter failure cannot write to the real host history.
                result = null
                runCatching {
                    val owner = instance ?: return@before
                    val paramsValue = playable.get(owner) ?: return@before
                    val value = argOrNull(0) ?: return@before
                    val aid = (host.call(paramsValue, "getAvid") as Number).toLong()
                    val cid = (host.call(paramsValue, "getCid") as Number).toLong()
                    val duration = (millis.invoke(null, host.call(value, "a")) as Number).toLong()
                    val position = (millis.invoke(null, host.call(value, "b")) as Number).toLong()
                    if (aid > 0 && cid > 0 && duration > 0 && position >= 0) {
                        library.recordVideo(LocalVirtualLibrary.Video(aid, cid,
                            host.call(paramsValue, "getTitle") as String, duration, 0, 0,
                            host.call(paramsValue, "getCover") as? String,
                            (host.call(paramsValue, "getMid") as Number).toLong(),
                            host.call(paramsValue, "getAuthor") as String))
                        library.recordProgress(aid, cid, position, duration)
                        env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.APPLIED)
                    }
                }.onFailure {
                    // Reflection errors must not escape PROTECT_HOST and resume host DB/report writes.
                    env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                }
            }
        }
        count++
        for (ownerName in listOf("com.bilibili.playerbizcommon.history.ugc.NormalMediaHistoryStorage",
                "com.bilibili.playerbizcommonv2.history.CommonMediaHistoryStorage")) {
            val storage = host.type(ownerName)
            for (method in Lookup.declaredMethods(storage) { it.name in setOf("read", "readFromDB", "write") }) {
                env.registrar.exact("guest.history.storage.$ownerName.${method.name}.${method.parameterTypes.joinToString { it.name }}",
                    storage, method.name, *method.parameterTypes) {
                    before {
                        if (!guest()) return@before
                        result = null
                        runCatching {
                            if (method.name != "write") {
                                val key = argOrNull(0)
                                val cid = if (key is String) key.substringAfterLast('_').toLongOrNull()
                                    else key?.let { runCatching { (host.call(it, "a") as Number).toLong() }.getOrNull() }
                                cid?.let(library::cachedResumePosition)?.let { position ->
                                    val entry = host.type("com.bilibili.player.history.MediaHistoryEntry")
                                    result = requireNotNull(Lookup.constructorOrNull(entry, classOf<Int>())).newInstance(position)
                                }
                            }
                        }.onFailure { env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR) }
                    }
                }
                count++
            }
        }
        return count
    }
}
