package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.atomic.AtomicBoolean

/** Stop producers before Neurons can persist a queue for replay outside the session. */
internal class IncognitoTelemetryHooks(private val session: IncognitoSession) {
    /** Resolve Neurons producers first; a missing anchor returns null so the whole feature can skip. */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val owner = PrivacyHostAccess(requireNotNull(env.classLoader))
            .type("com.bilibili.lib.neuron.api.Neurons")
        val producers = Lookup.declaredMethods(owner) {
            it.returnType == Void.TYPE && (it.name.startsWith("report") || it.name.startsWith("track"))
        }
        require(producers.any { it.name == "trackT" } && producers.any { it.name == "reportClick" })
        return@runCatching { applyProducers(env, owner, producers) }
    }.getOrNull()

    fun install(env: HookEnvironment): Int =
        prepare(env)?.invoke() ?: error("Neurons telemetry anchors unavailable")

    private fun applyProducers(env: HookEnvironment, owner: Class<*>, producers: List<java.lang.reflect.Method>): Int {
        val evidence = AtomicBoolean()
        for (method in producers) {
            env.registrar.exact("incognito.neurons.${method.name}.${method.parameterTypes.joinToString { it.name }}",
                owner, method.name, *method.parameterTypes) {
                before {
                    // Function0<Boolean> in track* is an upload condition, not a completion callback.
                    // Calling it here would run host code even though the event was discarded.
                    result = null
                    if (evidence.compareAndSet(false, true)) {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
                        env.reportStatus("incognito_telemetry_producer", "discarded")
                    }
                }
            }
        }
        env.registrar.exact("incognito.neurons.uuid.get", owner, "getUUID") {
            before { result = session.identity()?.sessionId.orEmpty() }
        }
        env.registrar.exact("incognito.neurons.uuid.set", owner, "setUUID", classOf<String>()) {
            before { result = null }
        }
        return producers.size + 2
    }
}
