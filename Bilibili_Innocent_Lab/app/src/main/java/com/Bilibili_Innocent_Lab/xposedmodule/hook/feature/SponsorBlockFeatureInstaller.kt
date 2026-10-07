package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorSeekAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorBlockClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerSessions
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorSegmentRepository
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

internal class SponsorBlockFeatureInstaller(private val enabled: Boolean, private val automatic: Boolean) : FeatureInstaller {
    override val id = ID
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val player = SponsorPlayerAccess.resolve(loader) ?: return FeatureInstallResult.Skipped("missing-player-structure")
        val seek = SponsorSeekAccess.resolve(loader, player) ?: return FeatureInstallResult.Skipped("missing-seek-guard")
        val detail = Lookup.classOrNull(loader, DETAIL)?.takeIf { Activity::class.java.isAssignableFrom(it) }
            ?: return FeatureInstallResult.Skipped("missing-detail-activity")
        val lifecycle = listOf("onResume", "onPause", "onDestroy").map { name ->
            Lookup.inheritedMethodOrNull(detail, name)?.takeIf { it.returnType == Void.TYPE }
                ?: return FeatureInstallResult.Skipped("missing-detail-lifecycle")
        }
        val seekMethod = Lookup.methodOrNull(player.wrapper.declaringClass, "seekTo", Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!) ?: return FeatureInstallResult.Skipped("missing-wrapper-seek")
        val sessions = SponsorPlayerSessions(environment, player, seek, detail, automatic,
            SponsorSegmentRepository(SponsorBlockClient(BuildConfig.VERSION_NAME)))
        val count = sessions.install(lifecycle, seekMethod)
        environment.reportStatus("sponsorblock_status", "success")
        return FeatureInstallResult.Installed(count)
    }
    companion object {
        const val ID = "sponsorblock"
        const val DETAIL = "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"
    }
}
