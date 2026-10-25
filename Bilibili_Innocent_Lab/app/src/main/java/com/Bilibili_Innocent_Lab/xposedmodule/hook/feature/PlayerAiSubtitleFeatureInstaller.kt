package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf

/** Client eligibility only. Language tracks and generation remain owned by the official server. */
internal class PlayerAiSubtitleFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        var installed = 0
        KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.dd.DeviceDecision")?.let { owner ->
            KavaMemberLookup.methodOrNull(owner, "getBoolean", classOf<String>(), classOf<Boolean>())
                ?.takeIf { it.returnType == classOf<Boolean>() }?.let { method ->
                    environment.registrar.exact("player.subtitle.ai.language", owner, method.name, *method.parameterTypes) {
                        after {
                            if (hasThrowable || argOrNull(0) != LANGUAGE_FLAG) return@after
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            if (result == false) {
                                result = true
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                            }
                        }
                    }
                    installed++
                }
        }
        KavaMemberLookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.Video\$DanmakuResolveParams")?.let { owner ->
            val request = KavaMemberLookup.methodOrNull(owner, "getRequestSubtitle")
                ?.takeIf { it.returnType == classOf<Boolean>() }
            val from = KavaMemberLookup.methodOrNull(owner, "getFrom")?.takeIf { it.returnType == classOf<String>() }
            val avid = KavaMemberLookup.methodOrNull(owner, "getAvid")?.takeIf { it.returnType == classOf<Long>() }
            val cid = KavaMemberLookup.methodOrNull(owner, "getCid")?.takeIf { it.returnType == classOf<Long>() }
            if (request != null && from != null && avid != null && cid != null) {
                environment.registrar.exact("player.subtitle.ai.request", owner, request.name) {
                    after {
                        if (hasThrowable) return@after
                        val params = instance ?: return@after
                        if (!eligible(from.invoke(params) as? String, avid.invoke(params) as Long, cid.invoke(params) as Long)) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        if (result == false) {
                            result = true
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                installed++
            }
        }
        if (installed == 0) return FeatureInstallResult.Skipped("missing-ai-subtitle-client-gates")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == 2)
    }

    companion object {
        const val ID = "player_ai_subtitle"
        const val LANGUAGE_FLAG = "ff_player_ai_language_subtitle"
        internal fun eligible(from: String?, avid: Long, cid: Long): Boolean =
            from != null && from != "live" && avid > 0 && cid > 0
    }
}
