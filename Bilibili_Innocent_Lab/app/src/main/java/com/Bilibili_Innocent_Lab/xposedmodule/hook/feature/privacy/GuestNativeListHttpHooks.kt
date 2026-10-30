package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import androidx.core.net.toUri
import com.highcapable.kavaref.extension.classOf
import java.io.IOException

/** Short circuit before interceptors, signing, HTTP cache and network; retain the host converter/callback executor. */
internal class GuestNativeListHttpHooks(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val call = host.type("com.bilibili.okretro.call.BiliCall")
        val response = host.type("okhttp3.Response")
        val convert = requireNotNull(locateConverter(call, response, host.type("retrofit2.Response"))) {
            "BiliCall response converter"
        }
        // The two flag fields drift by build (`m` is "executed" up to 9.09 but "canceled" from 9.10), so the
        // public isExecuted()/isCanceled() are used instead; the Type field is the only one of its type.
        val responseType = requireNotNull(locateResponseType(call)) { "BiliCall response type field" }
        val media = host.static(host.type("okhttp3.MediaType"), "parse", "application/json; charset=utf-8")
        val createBody = requireNotNull(Lookup.methodOrNull(host.type("okhttp3.ResponseBody"), "create", host.type("okhttp3.MediaType"), classOf<String>()))
        val protocol = requireNotNull(Lookup.fieldOrNull(host.type("okhttp3.Protocol"), "HTTP_1_1")).get(null)
        env.registrar.exact("guest.native.lists.http", call, "execute") {
            before {
                if (!isGuest()) return@before
                val target = instance ?: return@before
                val request = requireNotNull(host.call(target, "request"))
                val url = requireNotNull(host.call(request, "url"))
                val path = host.call(url, "encodedPath") as String
                if (!NativeFavoriteProtocol.handles(path) && !NativeWatchLaterProtocol.handles(path)) return@before
                // Set a failure BEFORE parsing local data: an adapter exception must never run the real request.
                throwable = IOException("Local library response unavailable")
                try {
                    check(host.call(target, "isCanceled") != true && host.call(target, "isExecuted") != true)
                    val params = linkedMapOf<String, String>()
                    repeat(host.call(url, "querySize") as Int) { index ->
                        params[host.call(url, "queryParameterName", index) as String] = host.call(url, "queryParameterValue", index) as? String ?: ""
                    }
                    val body = host.call(request, "body")
                    if (body != null && host.type("okhttp3.FormBody").isInstance(body)) repeat(host.call(body, "size") as Int) { index ->
                        params[host.call(body, "name", index) as String] = host.call(body, "value", index) as String
                    }
                    val json = library.nativeBlocking {
                        if (NativeWatchLaterProtocol.handles(path)) NativeWatchLaterProtocol.respond(path, params, it) { aid -> library.videoMetadata(aid, 0) }
                        else NativeFavoriteProtocol.respond(path, params, it)
                    }
                    // The same pin endpoint is decoded as BaseResponse by Favorites V2 and String by the older detail page.
                    val pinString = path == "/x/v3/fav/folder/add_top" &&
                        (responseType.get(target) as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.singleOrNull() == classOf<String>()
                    val payload = if (pinString) org.json.JSONObject(json).put("data", "").toString() else json
                    check(host.call(target, "isCanceled") != true)
                    val builder = host.create(host.type("okhttp3.Response\$Builder"))
                    host.call(builder, "request", request)
                    host.call(builder, "protocol", protocol)
                    host.call(builder, "code", 200)
                    host.call(builder, "message", "OK")
                    host.call(builder, "header", "Cache-Control", "no-store")
                    host.call(builder, "body", createBody.invoke(null, media, payload))
                    result = convert.invoke(target, host.call(builder, "build"))
                } catch (failure: Exception) {
                    throwable = IOException("Local library response unavailable", failure)
                    env.logError("guest_native_list_response", "[BIL] Native local list response failed")
                }
            }
        }
        // The remaining hooks are independent of the response short circuit above: a build that lacks one of them
        // (8.97+ has no cache getters, 9.0 has no `fa6.b`) must still get the local list responses.
        val missing = mutableListOf<String>()
        fun optional(name: String, action: () -> Unit) {
            runCatching(action).onFailure { missing += name }
        }
        for ((name, method) in mapOf("FavoritesActivityViewModel" to "P2", "FolderListViewModel" to "Q2", "FavoritesListViewModel" to "Z2")) {
            optional("cache.$name") {
                env.registrar.exact("guest.native.favorite.cache.$method", host.type("tv.danmaku.bili.ui.favorites.viewmodel.$name"), method) {
                    before { if (isGuest()) result = null }
                }
            }
        }
        optional("fragments") {
            val fragments = mapOf(
                "bilibili://user_center/favorite_v2/favorite/folders" to host.type("tv.danmaku.bili.ui.favorites.fragment.FavoritesFolderTabFragment"),
                "bilibili://user_center/favorite_v2/favorite/items" to host.type("tv.danmaku.bili.ui.favorites.fragment.FavoritesTabFragment"))
            env.registrar.exact("guest.native.favorite.fragments", host.type("com.bilibili.app.comm.list.widget.utils.ListExtentionsKt"),
                "findFragmentByUrl", classOf<android.content.Context>(), classOf<String>(), classOf<android.os.Bundle>()) {
                before {
                    if (!isGuest()) return@before
                    val type = fragments[(argOrNull(1) as? String)?.substringBefore('?')] ?: return@before
                    val fragment = host.create(type)
                    host.call(fragment, "setArguments", (argOrNull(2) as? android.os.Bundle)?.let { android.os.Bundle(it) } ?: android.os.Bundle())
                    result = fragment
                }
            }
        }
        optional("folder_route") {
            env.registrar.exact("guest.native.favorite.folder_route", host.type("fa6.b"), "a", classOf<String>(),
                host.type("androidx.fragment.app.Fragment")) {
                before {
                    if (!isGuest()) return@before
                    val uri = (argOrNull(0) as? String ?: return@before).toUri()
                    if (uri.scheme != "bilibili" || uri.host != "music" || !uri.path.orEmpty().startsWith("/playlist/detail/")) return@before
                    val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return@before
                    result = null
                    val fragment = argOrNull(1) ?: return@before
                    val activity = host.call(fragment, "getActivity") as? android.app.Activity ?: return@before
                    activity.startActivity(android.content.Intent().setClassName(activity.packageName,
                        "com.bilibili.playset.MultiTypeListDetailActivityV2").setData(uri).putExtra("playlistId", id))
                }
            }
        }
        optional("detail_owner") {
            val detail = host.type("com.bilibili.playset.MultiTypeListDetailActivityV2")
            env.registrar.exact("guest.native.favorite.detail_owner", detail, "Wc") { before { if (isGuest()) result = true } }
        }
        env.reportStatus("guest_native_lists_http", if (missing.isEmpty()) "adapted" else "adapted;skipped=${missing.joinToString(",")}")
    }

    companion object {
        /**
         * `BiliCall`'s `(okhttp3.Response) -> retrofit2.Response` converter. 8.84 to 8.96 declare it as `p` next to a
         * private `q` of the same shape; 8.97 and later keep one public final instance method, renamed `a`
         * (hostlab q56, 38/38 builds). The name wins where it exists; otherwise only a unique public shape is used.
         */
        internal fun locateConverter(call: Class<*>, response: Class<*>, result: Class<*>): java.lang.reflect.Method? =
            PrivacyStructuralResolver.method(call, "p", response)
                ?: PrivacyStructuralResolver.uniqueMethod(call) {
                    java.lang.reflect.Modifier.isPublic(it.modifiers) && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.singleOrNull() == response && it.returnType == result
                }

        /** The call's declared response [java.lang.reflect.Type]; the one field of that type in all 38 builds. */
        internal fun locateResponseType(call: Class<*>): java.lang.reflect.Field? =
            PrivacyStructuralResolver.uniqueField(call) { it.type == java.lang.reflect.Type::class.java }
    }
}
