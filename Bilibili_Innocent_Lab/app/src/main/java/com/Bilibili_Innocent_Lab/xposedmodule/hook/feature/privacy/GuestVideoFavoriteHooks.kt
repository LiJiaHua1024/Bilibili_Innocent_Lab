package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Let a guest favorite a video from the favorite star on the video page, into the local library.
 *
 * Tapping the star runs the host's shared login gate, which opens the login screen through the legacy router:
 * `Router.global().with(context).with("key_prompt_scene", ...).with("extend", json).open("activity://main/login/")`. The scene
 * of the star is `player.ugc-video-detail.user-action.fav.click` and `extend` is `{"oid":"<aid>","sid":"<id>"}`. The gate
 * itself (`bt1.o.a`) is inlined by R8 into its callers, so it cannot be hooked; `RouterProxy.open` can, and it is a public,
 * unobfuscated class whose `open(String)`, `open(Uri)`, `getMExtras` and `getMContext` exist with one shape on all 38 builds.
 *
 * For a guest the login request that comes from the favorite star is withheld and the video is toggled in the local
 * favorites instead. Anything else, and any star tap whose video metadata the local library does not know yet, keeps the
 * host's own behavior (the login screen), so a tap is never swallowed without an effect.
 *
 * The host star reads "is this video favorited" from a per-aid cache that the host's own success path writes with
 * `static void c(long aid, boolean favorited)` on an obfuscated class. That class is derived from the name-preserving
 * coroutine class `FavorRepository$favor$1` (its constructor takes the cache class first) and the writer is the single public
 * static `void(long, boolean)` on it, so a tap repaints the star from the toggle result: added turns it on, a second tap
 * turns it off. When the writer cannot be located the guest gets a "saved" toast instead, since nothing else shows the tap
 * worked. The host's own folder picker (a KMP dialog, obfuscated) is not opened; the module shows its own [FolderPicker] instead,
 * listing the local folders as checkboxes, and commits the exact selection (an empty selection un-favorites). Without a live
 * Activity to attach the dialog to, the tap falls back to toggling the default folder. The favorite count is not changed.
 * Status keys, kept apart because the channel keeps only the first value per key: `guest_video_favorite` is the install
 * report, `_state` says whether the star writer was located, `_applied` says a tap was taken over, `_picker` says the folder
 * picker was shown, `_saved` says the local write committed, `_repainted` says the star writer ran, and `_failed` says the
 * write did not commit.
 *
 * Verified on a 9.16.1 device only (`_applied`, `_saved` seen; the star turns pink on the first tap and grey on the second).
 */
internal class GuestVideoFavoriteHooks(
    private val store: FavoriteStore,
    private val isGuest: () -> Boolean,
    private val proxyClass: String = PROXY,
    /** Reads (scene, extend) from the proxy's extras bundle; replaceable because the Android Bundle is a stub in JVM tests. */
    private val readExtras: (Bundle?) -> Pair<String?, String?> = { it?.getString(SCENE_KEY) to it?.getString(EXTEND_KEY) },
    /** Locates the host's favorite-state writer; replaceable because the test fixtures have no such class. */
    private val locateState: (ClassLoader) -> Method? = ::locateFavoriteState,
    private val notify: (Context?, Boolean) -> Unit = { context, saved ->
        if (context != null) {
            val strings = GuestLibraryStrings.resolve(context)
            Toast.makeText(context, if (saved) strings.saved else strings.failure, Toast.LENGTH_SHORT).show()
        }
    },
    /** Asks which folders to save into; null (or a picker that cannot show) means the video goes to the default folder. */
    private val picker: FolderPicker? = null,
    /** The BLRouter classes the Story player's login request goes through; replaceable because the fixtures have other names. */
    private val routerNames: RouterNames = RouterNames(),
    /** Where the Story player's star gets its video from; null means the Story star is not handled. */
    private val storyStar: StoryStarSource? = null,
    /** Whether a call stack belongs to the Story player's favorite star; replaceable for the same reason. */
    private val isStoryStar: (Array<StackTraceElement>) -> Boolean = ::fromStoryFavoriteWidget
) {
    /** Class names of the router pieces the Story player's star login request is built from. */
    internal data class RouterNames(
        val router: String = "com.bilibili.lib.blrouter.BLRouter",
        val request: String = "com.bilibili.lib.blrouter.RouteRequest",
        val response: String = "com.bilibili.lib.blrouter.RouteResponse",
        val code: String = "com.bilibili.lib.blrouter.RouteResponse\$Code"
    )

    /** The video behind the Story star that was just tapped, plus a callback that keeps the Story item's own star state in step. */
    internal class StoryTap(val context: Context?, val video: LocalVirtualLibrary.Video, val markFavorite: (Boolean) -> Unit)

    internal interface StoryStarSource {
        /** Installs whatever the source needs from the host; false when this build does not have it. */
        fun install(env: HookEnvironment, loader: ClassLoader): Boolean
        /** The tap that is happening now, or null when the video behind it cannot be told. */
        fun tapped(): StoryTap?
    }
    /** One local favorite folder and whether the video is already in it. */
    internal data class Folder(val name: String, val checked: Boolean)

    /** Shows the folder choice. Returns false when it cannot be shown, so the caller falls back to the default folder. */
    internal interface FolderPicker {
        fun show(
            context: Context?,
            folders: List<Folder>,
            onConfirm: (selected: Set<String>) -> Unit,
            onCreate: (name: String, selected: Set<String>) -> Unit
        ): Boolean
    }

    /** What this hook needs from the local library. */
    internal interface FavoriteStore {
        fun metadata(aid: Long): LocalVirtualLibrary.Video?
        fun toggle(video: LocalVirtualLibrary.Video, onSaved: (added: Boolean) -> Unit, onFailed: () -> Unit)
        /** Every local folder, with [Folder.checked] set for the ones that already hold the video. Calls back on the main thread. */
        fun folders(aid: Long, callback: (List<Folder>) -> Unit)
        /** Makes [selected] the exact set of folders holding the video; an empty set removes it everywhere. */
        fun save(video: LocalVirtualLibrary.Video, selected: Set<String>, onSaved: () -> Unit, onFailed: () -> Unit)
        fun createFolder(name: String, onCreated: () -> Unit, onFailed: () -> Unit)
    }

    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val proxy = PrivacyStructuralResolver.type(loader, proxyClass)
        if (proxy == null) {
            env.reportStatus("guest_video_favorite", "absent")
            return@runCatching 0
        }
        val getExtras = PrivacyStructuralResolver.method(proxy, "getMExtras")?.takeIf { it.returnType == Bundle::class.java }
        val openString = PrivacyStructuralResolver.method(proxy, "open", String::class.java)?.takeIf { it.returnType == Void.TYPE }
        val openUri = PrivacyStructuralResolver.method(proxy, "open", Uri::class.java)?.takeIf { it.returnType == Void.TYPE }
        val getContext = PrivacyStructuralResolver.method(proxy, "getMContext")?.takeIf { Context::class.java.isAssignableFrom(it.returnType) }
        if (getExtras == null || getContext == null || (openString == null && openUri == null)) {
            env.reportStatus("guest_video_favorite", "absent")
            return@runCatching 0
        }
        val applied = AtomicBoolean(false)
        val saved = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val painted = AtomicBoolean(false)
        // The star reads "is favorited" from a per-aid cache the host writes on success; writing it is what repaints the star.
        val stateWriter = locateState(loader)
        env.reportStatus("guest_video_favorite_state", if (stateWriter != null) "installed" else "absent")
        val picked = AtomicBoolean(false)

        /** The local write committed: repaint the star from the result, or say "saved" when there is no star writer. */
        fun onCommitted(context: Context?, aid: Long, favorited: Boolean, after: (Boolean) -> Unit = {}) {
            if (saved.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_saved", "seen")
            after(favorited)
            if (stateWriter == null) notify(context, true)
            else runCatching { stateWriter.invoke(null, aid, favorited) }
                .onSuccess { if (painted.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_repainted", "seen") }
                .onFailure { env.logError("guest_video_favorite_state", "[BIL] Favorite star state not updated: ${it.javaClass.simpleName}") }
        }

        fun onFailure(context: Context?) {
            env.logError("guest_video_favorite_write", "[BIL] Local favorite write failed")
            if (failed.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_failed", "seen")
            notify(context, false)
        }

        fun toggleDefault(context: Context?, video: LocalVirtualLibrary.Video, after: (Boolean) -> Unit) =
            store.toggle(video, { added -> onCommitted(context, video.aid, added, after) }, { onFailure(context) })

        /** Opens the folder choice; a picker that cannot show (no live Activity) hands the tap to the default folder instead. */
        fun pick(context: Context?, video: LocalVirtualLibrary.Video, folders: List<Folder>, after: (Boolean) -> Unit) {
            val shown = picker?.show(context, folders,
                { selected ->
                    store.save(video, selected, { onCommitted(context, video.aid, selected.isNotEmpty(), after) }, { onFailure(context) })
                },
                { name, selected ->
                    store.createFolder(name, {
                        // Back to the picker with the new folder ticked and the earlier ticks kept.
                        store.folders(video.aid) { fresh ->
                            pick(context, video, fresh.map { it.copy(checked = it.name in selected || it.name == name) }, after)
                        }
                    }, { onFailure(context) })
                }) == true
            if (shown) {
                if (picked.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_picker", "seen")
            } else toggleDefault(context, video, after)
        }

        /** One star tap on a known video: the folder choice when there is a picker, otherwise the default folder. */
        fun startTap(context: Context?, video: LocalVirtualLibrary.Video, after: (Boolean) -> Unit = {}) {
            if (applied.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_applied", "seen")
            if (picker == null) toggleDefault(context, video, after)
            else store.folders(video.aid) { folders -> pick(context, video, folders, after) }
        }

        var hooks = 0
        for ((method, isUri) in listOfNotNull(openString?.let { it to false }, openUri?.let { it to true })) {
            env.registrar.exact("guest.video.favorite.${if (isUri) "uri" else "string"}", proxy, method.name, *method.parameterTypes) {
                before {
                    if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                    val target = instance ?: return@before
                    val route = argOrNull(0)?.toString()
                    val extras = runCatching { getExtras.invoke(target) as? Bundle }.getOrNull()
                    val context = runCatching { getContext.invoke(target) as? Context }.getOrNull()
                    val (scene, extend) = readExtras(extras)
                    val aid = favoriteAid(route, scene, extend) ?: return@before
                    val video = store.metadata(aid) ?: return@before
                    result = null
                    startTap(context, video)
                }
            }
            hooks++
        }
        hooks += installStoryStar(env, loader) { tap -> startTap(tap.context, tap.video, tap.markFavorite) }
        env.reportStatus("guest_video_favorite", "installed")
        hooks
    }.getOrElse {
        env.logError("guest_video_favorite", "[BIL] Guest video favorite unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_video_favorite", "failed")
        0
    }

    /**
     * The vertical Story player has its own star, `StoryFavoriteWidget`. Its login gate goes through `BLRouter.routeTo` with a
     * bare `bilibili://login` that carries no scene and no video, so the request alone cannot say which action it was. What
     * identifies it is the call stack (the widget's click handler is on it, and the class name is kept on all 38 builds), and
     * the video comes from the widget that was tapped ([StoryStarSource]). The router call must still return a response, so the
     * hook answers `RouteResponse(OK, request)` and the login screen is never started. When the video cannot be told, the login
     * screen stays, so a tap is never swallowed without an effect.
     */
    private fun installStoryStar(env: HookEnvironment, loader: ClassLoader, tap: (StoryTap) -> Unit): Int = runCatching {
        val source = storyStar ?: return@runCatching 0
        val router = PrivacyStructuralResolver.type(loader, routerNames.router) ?: return@runCatching 0
        val request = PrivacyStructuralResolver.type(loader, routerNames.request) ?: return@runCatching 0
        val response = PrivacyStructuralResolver.type(loader, routerNames.response) ?: return@runCatching 0
        val code = PrivacyStructuralResolver.type(loader, routerNames.code) ?: return@runCatching 0
        val routeTo = PrivacyStructuralResolver.method(router, "routeTo", request, Context::class.java) ?: return@runCatching 0
        val getTargetUri = PrivacyStructuralResolver.method(request, "getTargetUri") ?: return@runCatching 0
        val ok = code.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "OK" } ?: return@runCatching 0
        val newResponse = Lookup.constructorOrNull(response, code, request) ?: return@runCatching 0
        if (!source.install(env, loader)) return@runCatching 0
        env.registrar.exact("guest.video.favorite.story", router, routeTo.name, *routeTo.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val routeRequest = argOrNull(0) ?: return@before
                val uri = runCatching { getTargetUri.invoke(routeRequest)?.toString() }.getOrNull() ?: return@before
                if (uri.substringBefore('?').trimEnd('/') !in LOGIN_ROUTES) return@before
                if (!isStoryStar(Throwable().stackTrace)) return@before
                val tapped = runCatching { source.tapped() }.getOrNull()
                if (tapped == null) {
                    env.reportStatus("guest_video_favorite_story_unknown", "seen")
                    return@before
                }
                result = runCatching { newResponse.newInstance(ok, routeRequest) }.getOrNull() ?: return@before
                env.reportStatus("guest_video_favorite_story", "seen")
                tap(tapped)
            }
        }
        1
    }.getOrElse {
        env.logError("guest_video_favorite_story", "[BIL] Story star hook unavailable: ${it.javaClass.simpleName}")
        0
    }

    internal companion object {
        /** The Story player's favorite star widget; its name is kept (not obfuscated) on all 38 builds. */
        const val STORY_FAVORITE = "com.bilibili.video.story.action.widget.StoryFavoriteWidget"

        fun fromStoryFavoriteWidget(stack: Array<StackTraceElement>): Boolean =
            stack.any { it.className == STORY_FAVORITE || it.className.startsWith("$STORY_FAVORITE\$") }
        const val PROXY = "com.bilibili.lib.router.Router\$RouterProxy"
        /** The legacy router opens the login screen as `activity://main/login/` (seen on 9.16.1); BLRouter uses `bilibili://login`. */
        val LOGIN_ROUTES = setOf("activity://main/login", "bilibili://login")
        const val FAVORITE_SCENE_SUFFIX = ".user-action.fav.click"
        const val SCENE_KEY = "key_prompt_scene"
        const val EXTEND_KEY = "extend"

        /**
         * The aid of the video behind a favorite-star login request, or null when this request is anything else: another route,
         * another scene, or an extend payload without a positive `oid`.
         */
        fun favoriteAid(route: String?, scene: String?, extend: String?): Long? {
            if (route?.substringBefore('?')?.trimEnd('/') !in LOGIN_ROUTES) return null
            if (scene?.endsWith(FAVORITE_SCENE_SUFFIX) != true) return null
            val payload = extend?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { JSONObject(payload).optString("oid").toLongOrNull() }.getOrNull()?.takeIf { it > 0 }
        }

        /** The production store: the guest library's cached view metadata and its serialized write path. */
        fun storeFor(library: GuestLibraryRuntime): FavoriteStore = object : FavoriteStore {
            override fun metadata(aid: Long) = library.videoMetadata(aid, 0)
            override fun toggle(video: LocalVirtualLibrary.Video, onSaved: (Boolean) -> Unit, onFailed: () -> Unit) {
                // The mutation runs on the library's IO thread and the callback on another, so the result crosses threads.
                val added = AtomicBoolean(false)
                library.editCollections({ added.set(it.toggleFavorite(video)) }, { onSaved(added.get()) }, { onFailed() })
            }

            override fun folders(aid: Long, callback: (List<Folder>) -> Unit) {
                library.readCollections { collections ->
                    callback(collections.favorites.map { (name, videos) -> Folder(name, videos.any { it.aid == aid }) })
                }
            }

            override fun save(video: LocalVirtualLibrary.Video, selected: Set<String>, onSaved: () -> Unit, onFailed: () -> Unit) {
                library.editCollections({ it.setFavoriteFolders(video, selected) }, { onSaved() }, { onFailed() })
            }

            override fun createFolder(name: String, onCreated: () -> Unit, onFailed: () -> Unit) {
                library.editCollections({ it.createFavoriteFolder(name) }, { onCreated() }, { onFailed() })
            }
        }

        /**
         * The host keeps "is this video favorited" in one per-aid cache that the video page star reads; its success path ends in
         * `static void c(long aid, boolean favorited)`. The cache class is obfuscated, but the coroutine class of its `favor`
         * method keeps its name and takes the cache class as its first constructor parameter, so the owner is derived from
         * that and the writer is the single public static `void(long, boolean)` on it. Anything else returns null.
         */
        fun locateFavoriteState(loader: ClassLoader): Method? {
            val marker = PrivacyStructuralResolver.type(loader, FAVOR_COROUTINE) ?: return null
            val owner = Lookup.declaredConstructors(marker).singleOrNull()?.parameterTypes?.firstOrNull() ?: return null
            if (owner.isPrimitive || owner.isInterface || owner.isArray) return null
            return PrivacyStructuralResolver.uniqueMethod(owner) { method ->
                Modifier.isStatic(method.modifiers) && Modifier.isPublic(method.modifiers) && method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf<Class<*>>(java.lang.Long.TYPE, java.lang.Boolean.TYPE))
            }
        }

        const val FAVOR_COROUTINE = "com.bilibili.community.favorite.FavorRepository\$favor\$1"
    }
}
