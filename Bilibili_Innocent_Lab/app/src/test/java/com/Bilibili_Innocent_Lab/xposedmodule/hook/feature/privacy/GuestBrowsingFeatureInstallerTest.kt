package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.bilibili.app.dialogmanager.MainDialogManager.DialogManagerInfo
import com.bilibili.app.comment3.data.model.CommentSubjectDescription
import org.junit.Assert.*
import org.junit.Test

class GuestBrowsingFeatureInstallerTest {
    private class Harness {
        var guest = true
        var stateFailure = false
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val loader = GuestBrowsingFeatureInstallerTest::class.java.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        init {
            GuestBrowsingFeatureInstaller(true) {
                check(!stateFailure)
                guest
            }.install(env)
        }
    }

    @Test fun onlyPassiveLoginGuideIsRejectedAndOtherDialogsKeepTheirOriginalResult() {
        val h = Harness()
        var originalCalls = 0
        fun call(key: String?) = h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo(key), null)) { originalCalls++; true }
        assertEquals(false, call("login_guide"))
        assertEquals(0, originalCalls)
        listOf("login_guide_block", "permission", "teenager", null).forEach { assertEquals(true, call(it)) }
        assertEquals(4, originalCalls)
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun loginStateIsReadAtInvocationAndReadFailuresDoNotDismissUnrelatedDialogs() {
        val h = Harness()
        h.guest = false
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true })
        h.guest = true
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide", failRead = true), null)) { true })
        h.stateFailure = true
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true })
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun onePassLoginGuideIsSuppressedForGuestsOnly() {
        val h = Harness()
        assertTrue(h.registrar.registrationAttempts.any { it.first == "guest_browsing.one-pass-guide" })
        var ran = 0
        fun fire() = h.registrar.invoke("guest_browsing.one-pass-guide", args = arrayOf("home", null, true)) { ran++ }
        fire()
        assertEquals(0, ran)
        h.guest = false
        fire()
        assertEquals(1, ran)
    }

    @Test fun commentGuardLeavesServerDataAndPaginationMetadataUnchanged() {
        val h = Harness()
        val subject = CommentSubjectDescription(data = listOf("reply"), hasNext = true)
        assertEquals(false, h.registrar.invoke("guest_browsing.comment-guest-limit", subject) { subject.k() })
        assertTrue(subject.guestLimit)
        assertEquals(listOf("reply"), subject.data)
        assertTrue(subject.hasNext)
    }

    @Test fun playerLoginToastIsNotScheduledForGuestsAndOtherConfigReadsPassThrough() {
        val h = Harness()
        val config = com.bilibili.lib.config.BLRemoteConfig()
        fun read(key: String) = h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf(key, true)) { true }
        assertEquals(false, read("enable_player_force_login_toast"))
        assertEquals(true, read("enable_player_force_login_qn"))
        assertEquals(true, read("some_other_flag"))
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
        h.guest = false
        assertEquals(true, read("enable_player_force_login_toast"))
    }

    @Test fun guestQualityCapIsLiftedOnlyForTheForcedLoginKey() {
        val h = Harness()
        val config = com.bilibili.lib.config.BLRemoteConfig()
        fun read(key: String) = h.registrar.invoke("guest_browsing.guest-quality-cap-config", config,
            arrayOf(key, 32)) { 32 }
        assertEquals(Int.MAX_VALUE, read("enable_player_force_login_qn"))
        assertEquals(32, read("Memorable_qn"))
        h.guest = false
        assertEquals(32, read("enable_player_force_login_qn"))
    }

    @Test fun unrelatedConfigReadsNeverTouchAccountState() {
        val h = Harness()
        h.stateFailure = true
        val config = com.bilibili.lib.config.BLRemoteConfig()
        assertEquals(true, h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf("some_other_flag", true)) { true })
        assertEquals(32, h.registrar.invoke("guest_browsing.guest-quality-cap-config", config,
            arrayOf("some_other_int", 32)) { 32 })
        assertFalse(FeatureRuntimeStage.ERROR in h.stages)
        // 命中目标键但读不到登录态：放行宿主，并留下错误证据。
        assertEquals(true, h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf("enable_player_force_login_toast", true)) { true })
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun overseasForcedLoginIsLocatedByTheSingleStaticBooleanShape() {
        val h = Harness()
        val attempt = h.registrar.registrationAttempts.single { it.first == "guest_browsing.overseas-forced-login" }
        assertEquals("d", attempt.second.name)
        assertEquals(false, h.registrar.invoke("guest_browsing.overseas-forced-login") { true })
        h.guest = false
        assertEquals(true, h.registrar.invoke("guest_browsing.overseas-forced-login") { true })
    }

    @Test fun manualLoginAndPromptCleanupRemainCallable() {
        val h = Harness()
        val prompt = lp3.a()
        prompt.a()
        h.registrar.invoke("guest_browsing.force-login-toast", prompt) { prompt.run() }
        prompt.c()
        assertEquals(1, prompt.scheduled)
        assertEquals(0, prompt.shown)
        assertEquals(1, prompt.cleaned)
        val account = fixtures.privacy.manual.HomeUserCenterFragment()
        fixtures.privacy.manual.HomeUserCenterFragment.Tq(account, null)
        assertEquals(1, account.loginRequests)
        assertFalse(h.registrar.registrationAttempts.any { it.second.name == "Tq" })
    }
}
