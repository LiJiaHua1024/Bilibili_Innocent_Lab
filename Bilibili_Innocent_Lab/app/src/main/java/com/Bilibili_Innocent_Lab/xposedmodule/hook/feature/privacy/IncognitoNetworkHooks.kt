package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Per-process HTTP/RPC boundary. Adapter failures never send the original authenticated request. */
internal class IncognitoNetworkHooks(
    private val session: IncognitoSession,
    private val isolationReady: () -> Boolean = { true }
) {
    fun install(env: HookEnvironment): Int {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val client = host.type("okhttp3.OkHttpClient")
        val interceptor = host.type("okhttp3.Interceptor")
        val chainType = host.type("okhttp3.Interceptor\$Chain")
        val requestType = host.type("okhttp3.Request")
        val responseBuilder = host.type("okhttp3.Response\$Builder")
        val bodyType = host.type("okhttp3.ResponseBody")
        val mediaType = host.type("okhttp3.MediaType")
        val protocol = host.type("okhttp3.Protocol")
        val http11 = requireNotNull(Lookup.fieldOrNull(protocol, "HTTP_1_1")).get(null)
        val jsonType = host.static(mediaType, "parse", "application/json; charset=utf-8")
        val createBody = requireNotNull(Lookup.methodOrNull(bodyType, "create", mediaType, classOf<String>()))
        val request = requireNotNull(Lookup.methodOrNull(chainType, "request"))
        val proceed = requireNotNull(Lookup.methodOrNull(chainType, "proceed", requestType))
        val signType = host.type("com.bilibili.nativelibrary.LibBili")
        val formType = host.type("okhttp3.FormBody")
        val formBuilder = host.type("okhttp3.FormBody\$Builder")
        val appliedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun applied(path: String) {
            env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
            if (appliedPaths.add(path)) env.reportStatus("incognito_transport_$path", "local-success")
        }
        fun success(original: Any): Any {
            val builder = host.create(responseBuilder)
            host.call(builder, "request", original)
            host.call(builder, "protocol", http11)
            host.call(builder, "code", 200)
            host.call(builder, "message", "OK")
            host.call(builder, "header", "Cache-Control", "no-store")
            host.call(builder, "body", createBody.invoke(null, jsonType,
                "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{}}"))
            return requireNotNull(host.call(builder, "build"))
        }
        fun sanitize(original: Any, identity: IncognitoSession.Identity): Any {
            val builder = requireNotNull(host.call(original, "newBuilder"))
            listOf("Cookie", "Authorization", "Proxy-Authorization", "X-Bili-Access-Key", "X-Bili-Mid",
                "Display-ID", "X-Bili-Ticket", "Bili-Ticket", "X-Bili-Aurora-Eid").forEach { host.call(builder, "removeHeader", it) }
            listOf("Buvid", "Device-ID", "fp_local", "fp_remote", "GuestId").forEach {
                host.call(builder, "header", it, identity.buvid)
            }
            host.call(builder, "header", "Session-ID", identity.sessionId)
            host.call(builder, "header", "session_id", identity.sessionId)
            host.call(builder, "header", "Cache-Control", "no-store, no-cache")
            val url = requireNotNull(host.call(original, "url"))
            val params = linkedMapOf<String, String>()
            repeat(host.call(url, "querySize") as Int) { i ->
                params[host.call(url, "queryParameterName", i) as String] =
                    host.call(url, "queryParameterValue", i) as? String ?: ""
            }
            val clean = PrivacyNetworkPolicy.parameters(params, identity).toMutableMap()
            if (clean != params) {
                val signed = clean.remove("sign") != null
                val ub = requireNotNull(host.call(url, "newBuilder"))
                if (signed) host.call(ub, "encodedQuery", requireNotNull(host.static(signType, "signQuery", clean)).toString())
                else {
                    host.call(ub, "query", null)
                    clean.forEach { (k,v) -> host.call(ub, "addQueryParameter", k, v) }
                }
                host.call(builder, "url", requireNotNull(host.call(ub, "build")))
            }
            val old = host.call(original, "body")
            if (old != null && formType.isInstance(old)) {
                val values = linkedMapOf<String, String>()
                repeat(host.call(old, "size") as Int) { i ->
                    values[host.call(old, "name", i) as String] = host.call(old, "value", i) as String
                }
                val safe = PrivacyNetworkPolicy.parameters(values, identity).toMutableMap()
                if (safe != values) {
                    val signed = safe.remove("sign") != null
                    val replacement = if (signed) {
                        val value = requireNotNull(host.static(signType, "signQuery", safe)).toString()
                        val rb = host.type("okhttp3.RequestBody")
                        requireNotNull(Lookup.methodOrNull(rb, "create", mediaType, classOf<String>())).invoke(null,
                            host.static(mediaType, "parse", "application/x-www-form-urlencoded"), value)
                    } else {
                        val fb = host.create(formBuilder)
                        safe.forEach { (k,v) -> host.call(fb, "add", k, v) }
                        host.call(fb, "build")
                    }
                    host.call(builder, "method", host.call(original, "method"), replacement)
                }
            }
            return requireNotNull(host.call(builder, "build"))
        }
        val proxy = Proxy.newProxyInstance(host.loader, arrayOf(interceptor)) { p, method, args ->
            when (method.name) {
                "toString" -> "BIL incognito network boundary"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.getOrNull(0)
                "intercept" -> {
                    val chain = requireNotNull(args?.getOrNull(0))
                    val original = requireNotNull(request.invoke(chain))
                    try {
                        val url = requireNotNull(host.call(original, "url"))
                        if (!isolationReady() || PrivacyNetworkPolicy.blockHttp(host.call(url, "host") as String,
                                host.call(url, "encodedPath") as String, host.call(original, "method") as String)) {
                            applied("http")
                            success(original)
                        } else proceed.invoke(chain, sanitize(original, requireNotNull(session.identity())))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    } catch (e: Exception) {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                        throw IOException("Incognito request isolation failed", e)
                    }
                }
                else -> error("Unexpected interceptor method")
            }
        }
        env.registrar.exact("incognito.http.interceptors", client, "interceptors") {
            after {
                if (!hasThrowable) {
                    val existing = result as? List<*> ?: return@after
                    // Host application interceptors can add credentials or turn a read into a
                    // report. Check both before them (avoid enqueue/retry) and after them (the
                    // verified 8.90.2 native bridge is the next interceptor in okhttp3.g.f()).
                    result = listOf(proxy) + existing.filterNot { it === proxy } + proxy
                }
            }
        }
        env.registrar.exact("incognito.http.network_interceptors", client, "networkInterceptors") {
            after {
                if (!hasThrowable) {
                    val existing = result as? List<*> ?: return@after
                    // Standard socket requests, redirects and authentication follow-ups must be
                    // cleaned after the host network interceptors too, immediately before I/O.
                    result = existing.filterNot { it === proxy } + proxy
                }
            }
        }
        val noCookies = requireNotNull(Lookup.fieldOrNull(host.type("okhttp3.CookieJar"), "NO_COOKIES")).get(null)
        env.registrar.exact("incognito.http.cookies", client, "cookieJar") { before { result = noCookies } }
        // Verified in 8.90.2 okhttp3.g.f(): t06.a(client.a()) bypasses the public cache() getter.
        val cache = requireNotNull(Lookup.methodOrNull(client, "a")).also { require(it.returnType.name == "t06.d") }
        env.registrar.exact("incognito.http.cache", client, cache.name) { before { result = null } }
        env.registrar.exact("incognito.http.public_cache", client, "cache") { before { result = null } }
        val retro = host.type("com.bilibili.okretro.interceptor.DefaultRequestInterceptor")
        env.registrar.exact("incognito.http.sign", retro, "signQuery", classOf<Map<*, *>>()) {
            before {
                @Suppress("UNCHECKED_CAST")
                val params = argOrNull(0) as? Map<String, String> ?: return@before
                args[0] = PrivacyNetworkPolicy.parameters(params, requireNotNull(session.identity())).toMutableMap()
            }
        }
        val moss = host.type("com.bilibili.lib.moss.api.MossServiceImp")
        val descriptor = host.type("io.grpc.MethodDescriptor")
        val message = host.type("com.google.protobuf.GeneratedMessageLite")
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        val rule = host.type("com.bilibili.lib.moss.api.MossHttpRule")
        val readErrors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val onNext = requireNotNull(Lookup.methodOrNull(handler, "onNext", classOf<Any>()))
        val onComplete = requireNotNull(Lookup.methodOrNull(handler, "onCompleted"))
        fun reply(md: Any): Any {
            val marshaller = requireNotNull(host.call(md, "getResponseMarshaller"))
            return requireNotNull(host.call(marshaller, "parse", ByteArrayInputStream(byteArrayOf())))
        }
        for ((name, types) in listOf(
            "blockingUnaryCall" to arrayOf(descriptor, message, rule),
            "asyncUnaryCall" to arrayOf(descriptor, message, handler, rule)
        )) env.registrar.exact("incognito.rpc.$name", moss, name, *types) {
            before {
                val md = argOrNull(0)
                // Unknown descriptors fail closed as well: the protective framework would otherwise
                // retry the original RPC after a reflection/marshaller exception in this callback.
                val blocked = runCatching {
                    !isolationReady() || PrivacyNetworkPolicy.blockRpc(host.call(requireNotNull(md), "getFullMethodName") as String)
                }.getOrDefault(true)
                if (!blocked) {
                    // Only error class/code, once per RPC method; no URL, content, identity or token.
                    if (name == "asyncUnaryCall") argOrNull(2)?.let { callback ->
                        val rpc = runCatching { host.call(requireNotNull(md), "getFullMethodName") as String }.getOrDefault("unknown")
                        args[2] = Proxy.newProxyInstance(host.loader, arrayOf(handler)) { _, method, values ->
                            if (method.name == "onError" && readErrors.add(rpc)) {
                                val error = values?.getOrNull(0)
                                val code = error?.let { runCatching { host.call(it, "getCode") as? Number }.getOrNull() }
                                env.logError("incognito_rpc_read", "[BIL] Anonymous RPC failed: $rpc; type=${error?.javaClass?.simpleName}; code=$code")
                            }
                            try { method.invoke(callback, *(values ?: emptyArray())) }
                            catch (failure: InvocationTargetException) { throw failure.targetException }
                        }
                    }
                    return@before
                }
                result = null
                runCatching {
                    val response = reply(requireNotNull(md))
                    if (name == "blockingUnaryCall") result = response
                    applied("rpc")
                    if (name == "asyncUnaryCall") argOrNull(2)?.let { callback ->
                        onNext.invoke(callback, response)
                        onComplete.invoke(callback)
                    }
                }.onFailure {
                    // Keep result assigned even when a malformed reply or host callback fails.
                    // No exception may escape PROTECT_HOST and resume the original network call.
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                }
            }
        }
        // Client/bidirectional streams carry presence and activity (e.g. Reply/Room's
        // join/leave events). Give callers a live local sink, rather than null or an error
        // that causes reconnection; no original stream or queued request is created.
        for (name in listOf("asyncClientStreamingCall", "asyncBidiStreamingCall")) {
            env.registrar.exact("incognito.rpc.$name", moss, name, descriptor, handler) {
                before {
                    result = null
                    runCatching {
                        val callback = argOrNull(1)
                        val sink = IncognitoStreamSink(
                            acknowledge = { id -> callback?.let { host.call(it, "onUpstreamAck", id) } },
                            complete = { callback?.let { onComplete.invoke(it) } })
                        result = Proxy.newProxyInstance(host.loader, arrayOf(handler)) { proxy, method, values ->
                            when (method.name) {
                                "equals" -> proxy === values?.getOrNull(0)
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> "BIL local incognito stream"
                                "onNextForAck" -> sink.acceptForAck()
                                "onCompleted", "onError" -> { sink.close(); null }
                                else -> null
                            }
                        }
                        applied("stream")
                    }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
                }
            }
        }
        env.registrar.exact("incognito.rpc.asyncServerStreamingCall", moss, "asyncServerStreamingCall",
            descriptor, message, handler) {
            before {
                val blocked = runCatching { !isolationReady() || PrivacyNetworkPolicy.blockRpc(
                    host.call(requireNotNull(argOrNull(0)), "getFullMethodName") as String) }.getOrDefault(true)
                if (!blocked) return@before
                result = null
                runCatching {
                    argOrNull(2)?.let { callback -> onComplete.invoke(callback) }
                    applied("server_stream")
                }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }
            }
        }
        return 11
    }
}
