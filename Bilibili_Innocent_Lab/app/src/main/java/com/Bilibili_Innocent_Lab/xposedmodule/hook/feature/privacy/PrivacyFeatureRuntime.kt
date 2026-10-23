package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.classOf
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

internal class PrivacyFeatureRuntime(
    private val context: Context,
    private val noLogin: Boolean,
    private val incognito: Boolean,
    private val skipOnboarding: Boolean = false
) {
    fun installers(): List<FeatureInstaller> = listOf(
        FunctionalFeatureInstaller("incognito_privacy") { env ->
            if (!incognito) FeatureInstallResult.Skipped("disabled")
            else {
                val session = IncognitoSession().apply { enter() }
                // Keep reads sealed until every account/identity adapter has installed. An optional
                // host API disappearing must not leave an authenticated network path open.
                val ready = java.util.concurrent.atomic.AtomicBoolean(false)
                val network = IncognitoNetworkHooks(session) { ready.get() }.install(env)
                val count = runCatching { installAccountIsolation(env, session) }.getOrElse {
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                    env.reportStatus("incognito_privacy_status", "blocked:account-isolation")
                    return@FunctionalFeatureInstaller FeatureInstallResult.Installed(network, complete = false)
                }
                val web = IncognitoWebHooks().install(env)
                ready.set(true)
                activeSession = session
                activeIdentityBus = IncognitoIdentityBus(context, session) {
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                }.also { bus -> env.postToMain?.invoke { bus.register() } }
                env.reportStatus("incognito_privacy_status", "partial")
                // Network adapters are version-bound. Never report end-to-end privacy as verified.
                FeatureInstallResult.Installed(count + network + web, complete = false)
            }
        },
        FunctionalFeatureInstaller("guest_browsing") { env ->
            if (!noLogin && !incognito) FeatureInstallResult.Skipped("disabled")
            else if (env.processName != "tv.danmaku.bili") FeatureInstallResult.Skipped("non-main-process")
            else {
                val host = PrivacyHostAccess(requireNotNull(env.classLoader))
                val accounts = host.type("com.bilibili.lib.accounts.BiliAccounts")
                val get = requireNotNull(Lookup.methodOrNull(accounts, "get", classOf<Context>())) { "accounts-get-context" }
                val login = requireNotNull(Lookup.methodOrNull(accounts, "isLogin")) { "accounts-is-login" }
                val guest = { incognito || login.invoke(get.invoke(null, context)) == false }
                val library = GuestLibraryRuntime(context, incognito, guest) {
                    env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                }
                // The network and local memory share exactly one identity generation.
                if (incognito) activeSession?.let { network ->
                    library.useNetworkSession(network) { activeIdentityBus?.publish(it) }
                }
                env.postToMain?.invoke { library.preload() }
                val prompts = GuestBrowsingFeatureInstaller(true, guest).install(env)
                val onboarding = if (skipOnboarding) runCatching { GuestOnboardingHooks(guest).install(env) }.getOrElse {
                    env.logError("guest_onboarding", "[BIL] Guest onboarding adapter unavailable")
                    0
                } else 0
                val hooks = onboarding + GuestLibraryHostHooks(library, guest).install(env) +
                    GuestPlaybackHooks(library, guest).install(env) +
                    installMineOverlayGuard(env, guest)
                FeatureInstallResult.Installed(hooks + ((prompts as? FeatureInstallResult.Installed)?.hookCount ?: 0), complete = false)
            }
        }
    )

    private var activeSession: IncognitoSession? = null
    private var activeIdentityBus: IncognitoIdentityBus? = null

    private fun installMineOverlayGuard(env: HookEnvironment, guest: () -> Boolean): Int {
        val fragment = Lookup.classOrNull(env.classLoader, "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment")
            ?: return 0
        val method = Lookup.methodOrNull(fragment, "Ir", classOf<Boolean>()) ?: return 0
        // Ir(boolean) only creates/shows HomeUserCenterForceLoginFragment; it is not the login button.
        env.registrar.exact("guest.mine.overlay", fragment, method.name, *method.parameterTypes) {
            before { if (guest()) result = null }
        }
        return 1
    }

    private fun installAccountIsolation(env: HookEnvironment, session: IncognitoSession): Int {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        var count = 0
        val accounts = host.type("com.bilibili.lib.accounts.BiliAccounts")
        val values: Map<String, Any?> = mapOf(
            "isLogin" to false, "isTokenValid" to false, "isTokenExpired" to true,
            "getAccessKey" to "", "loadAccessTokenString" to null, "loadAccessTokenMid" to 0L,
            "mid" to 0L, "getAccessToken" to null, "loadAccessToken" to null,
            "getAccountCookie" to null, "hasFastLogin" to false, "hasFirstLogin" to false,
            "getFastLoginInfo" to null, "fetchAvailableAccounts" to emptyList<Any>()
        )
        values.forEach { (name, value) ->
            val m = requireNotNull(Lookup.methodOrNull(accounts, name))
            env.registrar.exact("incognito.account.$name", accounts, name, *m.parameterTypes) { before { result = value } }
            count++
        }
        // Guest responses must never expire/revoke the real account persisted before the session.
        Lookup.declaredMethods(accounts) { it.returnType == Void.TYPE &&
            it.name in setOf("logout", "logoutByUser", "clearAccessToken", "clearAccessTokenByAuthJsb",
                "clearAccessTokenModel", "clearFastLogin", "signedInWithToken") }.forEach { m ->
            env.registrar.exact("incognito.account.preserve.${m.name}.${m.parameterCount}", accounts, m.name, *m.parameterTypes) {
                before { result = null }
            }
            count++
        }
        for (ownerName in listOf("com.bilibili.lib.biliid.api.BuvidHelper", "com.bilibili.api.BiliConfig")) {
            val owner = host.type(ownerName)
            Lookup.declaredMethods(owner) { it.returnType == classOf<String>() && it.parameterCount == 0 &&
                it.name in setOf("getBuvid", "getLocalBuvid", "getRemoteBuvid", "softGetBuvid",
                    "getFpLocal", "getFpRemote", "getFp", "getSessionId", "getGuestId", "getAccessKey") }.forEach { m ->
                env.registrar.exact("incognito.identity.$ownerName.${m.name}", owner, m.name) {
                    before {
                        result = when (m.name) {
                            "getAccessKey" -> ""
                            "getSessionId" -> session.identity()!!.sessionId
                            else -> session.identity()!!.buvid
                        }
                    }
                }
                count++
            }
        }

        for (name in listOf("com.bilibili.api.PhoneDeviceId", "com.bilibili.api.DisplayID")) {
            val owner = host.type(name)
            env.registrar.exact("incognito.identity.$name", owner, "get") {
                before { result = session.identity()!!.buvid }
            }
            count++
        }
        // Protobuf serializers read fields directly. Intercept setters, not just getters.
        for ((name, setters) in mapOf(
            "com.bapis.bilibili.metadata.Metadata" to mapOf("setAccessKey" to false, "setBuvid" to true),
            "com.bapis.bilibili.metadata.device.Device" to mapOf("setBuvid" to true, "setFpLocal" to true, "setFpRemote" to true, "setFp" to true, "setGuestId" to true)
        )) {
            val owner = Lookup.classOrNull(host.loader, name) ?: continue
            setters.forEach { (setter, fingerprint) ->
                val m = Lookup.methodOrNull(owner, setter, classOf<String>()) ?: return@forEach
                env.registrar.exact("incognito.metadata.$name.$setter", owner, m.name, *m.parameterTypes) {
                    before { args[0] = if (fingerprint) session.identity()!!.buvid else "" }
                }
                count++
            }
        }
        // 8.90.2 hv2.a creates the actual RPC metadata bytes and identify_v1 header.
        // Clean the serialized message too: cached builders/ByteString setters bypass String hooks.
        val metadataFactory = host.type("hv2.a")
        for ((method, value) in mapOf("l" to { "" }, "a" to { session.identity()!!.buvid })) {
            env.registrar.exact("incognito.rpc.identity.$method", metadataFactory, method) {
                before { result = value() }
            }
            count++
        }
        val metadataEvidence = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        for ((method, typeName) in mapOf("p" to "com.bapis.bilibili.metadata.Metadata",
            "m" to "com.bapis.bilibili.metadata.device.Device")) {
            val type = host.type(typeName)
            env.registrar.exact("incognito.rpc.metadata.$method", metadataFactory, method) {
                after {
                    if (hasThrowable) return@after
                    val bytes = result as? ByteArray
                    // Set the safe fallback before reflection. PROTECT_HOST must never restore
                    // the original credentials when a future host changes this adapter.
                    result = byteArrayOf()
                    runCatching {
                        val original = requireNotNull(host.static(type, "parseFrom", requireNotNull(bytes)))
                        val builder = requireNotNull(host.call(original, "toBuilder"))
                        val buvid = requireNotNull(session.identity()).buvid
                        host.call(builder, "setBuvid", buvid)
                        if (method == "p") host.call(builder, "setAccessKey", "")
                        else {
                            for (setter in listOf("setFp", "setFpLocal", "setFpRemote", "setGuestId"))
                                host.call(builder, setter, buvid)
                            host.call(builder, "setFts", 0L)
                        }
                        result = host.call(requireNotNull(host.call(builder, "build")), "toByteArray")
                        if (metadataEvidence.add(method)) env.reportStatus("incognito_rpc_metadata_$method", "sanitized")
                    }.onFailure {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                        if (metadataEvidence.add("error-$method")) env.logError("incognito_rpc_metadata_$method",
                            "[BIL] RPC metadata adapter failed: ${it.javaClass.simpleName}; " +
                                if (it is IllegalArgumentException) it.message.orEmpty() else "host-call-failed")
                    }
                }
            }
            count++
        }
        count += IncognitoTelemetryHooks(session).install(env)
        val provider = host.type("com.bilibili.search2.provider.BiliSearchSuggestionProvider")
        env.registrar.exact("incognito.search.query", provider, "query", classOf<android.net.Uri>(),
            classOf<Array<String>>(), classOf<String>(), classOf<Array<String>>(), classOf<String>()) {
            before {
                result = android.database.MatrixCursor(arrayOf("_id", "suggest_intent_query", "suggest_text_1", "suggest_icon_1"))
            }
        }
        count++
        // Android's search provider must neither write nor erase the user's pre-session history.
        val recent = classOf<android.provider.SearchRecentSuggestions>()
        env.registrar.exact("incognito.search.save", recent, "saveRecentQuery", classOf<String>(), classOf<String>()) {
            before { result = null }
        }
        env.registrar.exact("incognito.search.clear", recent, "clearHistory") { before { result = null } }
        return count + 2
    }
}
