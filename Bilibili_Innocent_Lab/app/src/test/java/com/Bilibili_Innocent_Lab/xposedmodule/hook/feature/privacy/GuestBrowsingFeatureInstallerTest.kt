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
