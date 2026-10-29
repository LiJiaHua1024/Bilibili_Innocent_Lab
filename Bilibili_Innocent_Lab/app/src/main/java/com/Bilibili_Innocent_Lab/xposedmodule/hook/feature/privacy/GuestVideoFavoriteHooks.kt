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
 * worked. This is still the short path: the host's folder picker (the KMP favorite dialog) is not opened, so everything goes
 * to the default folder, and the favorite count is not changed.
 * Status keys, kept apart because the channel keeps only the first value per key: `guest_video_favorite` is the install
 * report, `_state` says whether the star writer was located, `_applied` says a tap was taken over, `_saved` says the local
 * write committed, `_repainted` says the star writer ran, and `_failed` says the write did not commit.
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
    }
) {
    /** The two things this hook needs from the local library. */
    internal interface FavoriteStore {
        fun metadata(aid: Long): LocalVirtualLibrary.Video?
        fun toggle(video: LocalVirtualLibrary.Video, onSaved: (added: Boolean) -> Unit, onFailed: () -> Unit)
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
                    if (applied.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_applied", "seen")
                    store.toggle(video, { added ->
                        if (saved.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_saved", "seen")
                        if (stateWriter == null) notify(context, true)
                        else runCatching { stateWriter.invoke(null, aid, added) }
                            .onSuccess { if (painted.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_repainted", "seen") }
                            .onFailure { env.logError("guest_video_favorite_state", "[BIL] Favorite star state not updated: ${it.javaClass.simpleName}") }
                    }, {
                        env.logError("guest_video_favorite_write", "[BIL] Local favorite write failed")
                        if (failed.compareAndSet(false, true)) env.reportStatus("guest_video_favorite_failed", "seen")
                        notify(context, false)
                    })
                }
            }
            hooks++
        }
        env.reportStatus("guest_video_favorite", "installed")
        hooks
    }.getOrElse {
        env.logError("guest_video_favorite", "[BIL] Guest video favorite unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_video_favorite", "failed")
        0
    }

    internal companion object {
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
