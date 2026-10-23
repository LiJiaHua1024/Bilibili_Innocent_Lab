package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.app.Dialog
import android.content.Context
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 8.90.2 native favorite state, animations, snackbars and folder sheet; only storage is replaced. */
internal class GuestNativeFavoriteHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean
) {
    private val relations = java.util.Collections.synchronizedMap(WeakHashMap<Any, Unit>())
    private val sheets = WeakHashMap<Any, Map<Long, String>>()
    @Volatile private var snapshot = LocalVirtualLibrary.Collections()

    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val king = host.type(KING)
        val relation = host.type(RELATION)
        val dialog = host.type(DIALOG)
        val playSet = host.type("com.bilibili.playset.widget.favorite.PlaySet")
        val page = host.type("com.bilibili.playset.widget.favorite.PlaySetPageData")
        val pageConstructor = requireNotNull(Lookup.constructorOrNull(page, classOf<Int>(), classOf<List<*>>(),
            host.type("com.bilibili.playset.widget.favorite.PlaySeason"), classOf<Boolean>()))
        val json = host.type("com.google.gson.JsonObject")
        fun field(target: Any, name: String): Any? = requireNotNull(Lookup.fieldOrNull(target.javaClass, name)).get(target)
        fun aid(target: Any): Long = (host.call(requireNotNull(field(target, "c")), "a") as Number).toLong()
        fun selected(aid: Long, state: LocalVirtualLibrary.Collections = snapshot) =
            state.favorites.values.any { videos -> videos.any { it.aid == aid } }
        fun update(state: LocalVirtualLibrary.Collections) {
            snapshot = state
            synchronized(relations) { relations.keys.toList() }.forEach { target ->
                runCatching { host.call(target, "I", selected(aid(target), state)) }
                    .onFailure { env.logError("guest_native_favorite_state", "[BIL] Native favorite state update failed") }
            }
        }
        library.observeCollections(::update)
        library.readCollections(::update)

        val constructor = requireNotNull(Lookup.constructorOrNull(relation, classOf<Context>(),
            host.type("kotlinx.coroutines.CoroutineScope"), host.type("com.bapis.bilibili.app.viewunite.v1.ViewReply"),
            host.type("com.bilibili.ship.theseus.united.page.view.a"), host.type("ky3.a"),
            host.type("com.bilibili.ship.theseus.united.di.BusinessType")))
        env.registrar.constructor("guest.favorite.native.relation", constructor) {
            after {
                if (!isGuest() || hasThrowable) return@after
                val target = instance ?: return@after
                relations[target] = Unit
                val weak = WeakReference(target)
                library.readCollections { state ->
                    snapshot = state
                    weak.get()?.let { host.call(it, "I", selected(aid(it), state)) }
                }
            }
        }
        // Late anonymous relation responses must not erase the local selection.
        env.registrar.exact("guest.favorite.native.relation_write", relation, "U", classOf<Boolean>()) {
            before { if (isGuest()) instance?.let { args[0] = selected(aid(it)) } }
        }
        fun video(service: Any): LocalVirtualLibrary.Video {
            val repository = requireNotNull(field(service, "h"))
            return PrivacyVideoMetadata.read(host, requireNotNull(field(repository, "b"))).copy(
                aid = (host.call(repository, "a") as Number).toLong(),
                cid = (host.call(repository, "d") as Number).toLong()).also(library::recordVideo)
        }
        fun resource(context: Context, name: String): String {
            val id = requireNotNull(Lookup.fieldOrNull(host.type("bili.resource.playerbaseres.ju0"), name)).getInt(null)
            return context.getString(id)
        }
        env.registrar.exact("guest.favorite.native.click", king, "B0") {
            before {
                if (!isGuest()) return@before
                result = null
                val service = instance ?: return@before
                val target = video(service)
                val weak = WeakReference(service)
                var added = false
                library.editCollections({ added = it.toggleFavorite(target) }, { state ->
                    // Commit first, then use the host's own state/animation/snackbar path.
                    snapshot = state
                    weak.get()?.let { live ->
                        val context = field(live, "b") as Context
                        if (added) host.call(live, "D0", true, resource(context, "p5"), 0)
                        else {
                            host.call(requireNotNull(field(live, "i")), "I", false)
                            host.static(host.type("com.bilibili.ship.theseus.united.utils.s"), "d", resource(context, "d1"))
                        }
                    }
                })
            }
        }
        env.registrar.exact("guest.favorite.native.long_click", king, "C0") {
            before {
                if (!isGuest()) return@before
                result = null
                val service = instance ?: return@before
                video(service)
                host.call(service, "V0", false)
            }
        }
        // Also covers the native snackbar's "modify" action which enters V0 directly.
        env.registrar.exact("guest.favorite.native.prepare_sheet", king, "V0", classOf<Boolean>()) {
            before { if (isGuest()) instance?.let { video(it) } }
        }
        env.registrar.exact("guest.favorite.native.sheet_load", dialog, "d0") {
            before {
                if (!isGuest()) return@before
                result = null
                val target = instance ?: return@before
                val weak = WeakReference(target)
                library.readCollections { state ->
                    val live = weak.get() ?: return@readCollections
                    if (!(live as Dialog).isShowing) return@readCollections
                    val extra = requireNotNull(field(live, "b"))
                    val archive = (host.call(extra, "j") as Number).toLong()
                    val names = state.favorites.keys.toList()
                    val ids = names.associateBy { NativeFavoriteFolders.id(state, it) }
                    require(ids.size == names.size) { "Local folder ID collision" }
                    sheets[live] = ids
                    val rows = ids.map { (id, name) ->
                        val videos = state.favorites.getValue(name)
                        host.create(playSet).also { row ->
                            host.call(row, "setId", id)
                            host.call(row, "setTitle", name)
                            host.call(row, "setAttr", if (name == LocalVirtualLibrary.DEFAULT_FOLDER) 1 else 3)
                            host.call(row, "setContentCounts", videos.map { it.aid }.distinct().size)
                            host.call(row, "setFavorite", if (videos.any { it.aid == archive }) 1 else 0)
                            host.call(row, "setCover", state.folderInfo[name]?.cover?.takeIf { it.isNotEmpty() } ?: videos.firstOrNull()?.cover.orEmpty())
                        }
                    }
                    host.call(live, "Q", pageConstructor.newInstance(rows.size, rows, null, false))
                }
            }
        }
        // Use the native checkbox map (not add-only deltas) for one atomic multi-folder update.
        env.registrar.exact("guest.favorite.native.sheet_commit", dialog, "D") {
            before {
                if (!isGuest()) return@before
                result = null
                val target = instance ?: return@before
                val extra = requireNotNull(field(target, "b"))
                val archive = (host.call(extra, "j") as Number).toLong()
                val cid = (host.call(extra, "c") as Number).toLong()
                val current = requireNotNull(library.videoMetadata(archive, cid))
                val adapter = requireNotNull(field(target, "m"))
                val checked = field(adapter, "d") as Map<*, *>
                val names = requireNotNull(sheets[target])
                val chosen = names.filterKeys { checked[it] == true }.values.toSet()
                val weak = WeakReference(target)
                library.editCollections({ it.setFavoriteFolders(current, chosen) }, { state ->
                    snapshot = state
                    weak.get()?.takeIf { (it as Dialog).isShowing }?.let { host.call(it, "O", host.create(json)) }
                })
            }
        }
        val laterId = requireNotNull(Lookup.fieldOrNull(host.type("com.bilibili.playset.i5"), "a3")).getInt(null)
        env.registrar.exact("guest.favorite.native.sheet_later", dialog, "onClick", classOf<View>()) {
            before {
                if (!isGuest() || (argOrNull(0) as? View)?.id != laterId) return@before
                result = null
                val target = instance ?: return@before
                val extra = requireNotNull(field(target, "b"))
                val current = requireNotNull(library.videoMetadata((host.call(extra, "j") as Number).toLong(),
                    (host.call(extra, "c") as Number).toLong()))
                val weak = WeakReference(target)
                library.editCollections({ it.addWatchLater(current) }, {
                    weak.get()?.let { live ->
                        val context = (live as Dialog).context
                        host.static(host.type("com.bilibili.droid.ToastHelper"), "showToastShort", context,
                            GuestLibraryStrings.resolve(context).saved)
                        live.dismiss()
                    }
                })
            }
        }
        GuestNativeFolderEditorHooks(library, isGuest).install(env) {
            sheets.keys.toList().forEach { sheet -> if ((sheet as Dialog).isShowing) host.call(sheet, "d0") }
        }
        env.reportStatus("guest_native_favorite", "adapted")
    }

    companion object {
        private const val KING = "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionService"
        private const val RELATION = "com.bilibili.ship.theseus.united.page.view.RelationRepository"
        private const val DIALOG = "com.bilibili.playset.widget.favorite.FavoriteDialog"
    }
}
