package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answer a guest's favorites and watch-later list reads on the KMP pages from the local library.
 *
 * The 9.x favorites and watch-later pages send their list requests through Ktor on the native "ktor-ignet" engine
 * (`kntr.base.ktor.ignet.IgnetHttpEngine`), not through okretro, so [GuestNativeListHttpHooks] never sees them. An
 * `IgHttpResponse` cannot be created on the Java side (its body lives in native memory), so the request still goes out
 * (sealed by [IgnetHttpBoundary] when incognito is on) and this class replaces what the engine reads from the response.
 * On all 38 supported builds the engine reads exactly `httpCode()`, `httpMessage()`, `protocol()`, `headers()` and then
 * `readDataV2(byte[], int)` in a loop until it returns a value that is not positive, then `close()` (hostlab q32).
 * `IgHttpRequest.execute()`, `url()` and `method()` have the same shape on all 38 builds as well.
 *
 * Only GET requests to [READ_PATHS] on [HOSTS] are answered, so a write is never acknowledged without being applied. A
 * request whose local answer fails keeps the server's reply, which the page shows as a load failure.
 * Status keys: `guest_ignet_library` (installed/absent/failed), `_answered` (a list was served), `_failed`.
 */
internal class GuestIgnetLibraryResponses(
    private val isGuest: () -> Boolean,
    /** Local JSON for a read path and its query, or null when the library has no answer for it. */
    private val answer: (path: String, params: Map<String, String>) -> String?,
    private val requestClass: String = REQUEST,
    private val responseClass: String = RESPONSE
) {
    /** A replaced body; read by the engine's single reader coroutine, guarded anyway. */
    private class Body(val bytes: ByteArray) {
        private var offset = 0

        @Synchronized
        fun read(target: ByteArray, max: Int): Int {
            val count = minOf(max, target.size, bytes.size - offset)
            if (count <= 0) return 0
            System.arraycopy(bytes, offset, target, 0, count)
            offset += count
            return count
        }
    }

    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val request = PrivacyStructuralResolver.type(loader, requestClass)
        val response = PrivacyStructuralResolver.type(loader, responseClass)
        if (request == null || response == null) {
            env.reportStatus("guest_ignet_library", "absent")
            return@runCatching 0
        }
        val execute = PrivacyStructuralResolver.method(request, "execute")?.takeIf { it.returnType == response }
        val url = PrivacyStructuralResolver.method(request, "url")?.takeIf { it.returnType == String::class.java }
        val method = PrivacyStructuralResolver.method(request, "method")?.takeIf { it.returnType == String::class.java }
        val httpCode = PrivacyStructuralResolver.method(response, "httpCode")?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        val httpMessage = PrivacyStructuralResolver.method(response, "httpMessage")?.takeIf { it.returnType == String::class.java }
        val headers = PrivacyStructuralResolver.method(response, "headers")?.takeIf { Map::class.java.isAssignableFrom(it.returnType) }
        val read = PrivacyStructuralResolver.method(response, "readDataV2", ByteArray::class.java, Int::class.javaPrimitiveType!!)
            ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        if (execute == null || url == null || method == null || httpCode == null || httpMessage == null || headers == null || read == null) {
            env.reportStatus("guest_ignet_library", "absent")
            return@runCatching 0
        }
        // Weak: a response the host drops is never kept alive here.
        val bodies: MutableMap<Any, Body> = Collections.synchronizedMap(WeakHashMap())
        val answered = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        fun bodyOf(target: Any?): Body? = if (target == null || bodies.isEmpty()) null else bodies[target]

        env.registrar.exact("guest.ignet.library.execute", request, execute.name) {
            after {
                if (hasThrowable || !runCatching { isGuest() }.getOrDefault(false)) return@after
                val target = instance ?: return@after
                val reply = result ?: return@after
                val list = runCatching { listRead(method.invoke(target) as? String, url.invoke(target) as? String) }.getOrNull() ?: return@after
                runCatching { answer(list.first, list.second) }
                    .onSuccess { json ->
                        if (json == null) return@onSuccess
                        bodies[reply] = Body(json.toByteArray(Charsets.UTF_8))
                        if (answered.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_answered", list.first)
                    }
                    .onFailure {
                        env.logError("guest_ignet_library_answer", "[BIL] Local list answer failed: ${it.javaClass.simpleName}")
                        if (failed.compareAndSet(false, true)) env.reportStatus("guest_ignet_library_failed", list.first)
                    }
            }
        }
        env.registrar.exact("guest.ignet.library.code", response, httpCode.name) {
            before { if (bodyOf(instance) != null) result = 200 }
        }
        env.registrar.exact("guest.ignet.library.message", response, httpMessage.name) {
            before { if (bodyOf(instance) != null) result = "OK" }
        }
        env.registrar.exact("guest.ignet.library.headers", response, headers.name) {
            // No Content-Encoding or Content-Length from the server reply: the body below is plain and has its own length.
            before { if (bodyOf(instance) != null) result = mapOf(CONTENT_TYPE to JSON_TYPE) }
        }
        env.registrar.exact("guest.ignet.library.read", response, read.name, *read.parameterTypes) {
            before {
                val body = bodyOf(instance) ?: return@before
                val target = argOrNull(0) as? ByteArray
                result = if (target == null) 0 else body.read(target, argOrNull(1) as? Int ?: 0)
            }
        }
        env.reportStatus("guest_ignet_library", "installed")
        5
    }.getOrElse {
        env.logError("guest_ignet_library", "[BIL] Guest ignet library unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_ignet_library", "failed")
        0
    }

    internal companion object {
        const val REQUEST = "com.bilibili.lib.ighttp.IgHttpRequest"
        const val RESPONSE = "com.bilibili.lib.ighttp.IgHttpResponse"
        const val CONTENT_TYPE = "Content-Type"
        const val JSON_TYPE = "application/json; charset=utf-8"
        val HOSTS = setOf("api.bilibili.com", "app.bilibili.com", "api.biliapi.net", "app.biliapi.net")

        /** List reads of the favorites and watch-later pages. Writes are left alone on purpose. */
        val READ_PATHS = setOf(
            "/x/v3/fav/tab", "/x/v3/fav/tab/my_fav", "/x/v3/fav/tab/fav", "/x/v3/fav/tab/fav/search",
            "/x/v3/fav/tab/fol", "/x/v3/fav/folder/resources", "/x/v3/fav/folder/info",
            "/x/v2/history/toview", "/x/v2/history/toview/v2/list"
        )

        /** (path, query) of a guest list read this class answers, or null for anything else. */
        fun listRead(method: String?, url: String?): Pair<String, Map<String, String>>? {
            if (!method.equals("GET", ignoreCase = true) || url.isNullOrBlank()) return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host?.lowercase() !in HOSTS) return null
            val path = uri.rawPath?.trimEnd('/')?.takeIf { it in READ_PATHS } ?: return null
            val params = linkedMapOf<String, String>()
            uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.forEach { pair ->
                val name = decode(pair.substringBefore('=')) ?: return@forEach
                params[name] = decode(pair.substringAfter('=', "")) ?: ""
            }
            return path to params
        }

        private fun decode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }
}
