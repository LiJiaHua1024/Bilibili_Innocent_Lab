package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 仅在专项有线备用机显式运行：改变测试配置并冷启动真实宿主；不调用伪造 UID 的端点入口。 */
@RunWith(AndroidJUnit4::class)
class NPatchDirectHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun managerStoppedSettingsChangeAndDisableUseCurrentDirectAuthority() {
        verifyCurrentDirectAuthority(NPatchMode.MANAGER)
    }

    internal fun verifyCurrentDirectAuthority(mode: NPatchMode) {
        assertEquals(mode, NPatchRuntimeProfiles.read(context).mode)
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        assertTrue(CommunicationCompatibilityStore.setEnabled(context, false))
        assertTrue(NoRootSupportStore.disable(context))
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        val first = PublicationAuthorityStore.current(context)!!
        shell("am force-stop top.nkbe.npatch")
        restartHost()
        val running = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(HostAdmissionContract.DIRECT, running.bootstrap.configSource)
        assertEquals(first.identity, running.bootstrap.admission)
        assertTrue(running.bootstrap.hookPointInstalledCount > 0)
        if (mode == NPatchMode.EMBEDDED) assertProcess(NPatchProcessState.INSTALLED, mode)

        val prefs = context.modulePreferences()
        val key = RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION
        assertTrue(prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1).commit())
        val updated = PublicationAuthorityStore.current(context)!!
        assertNotEquals(first.identity, updated.identity)
        restartHost()
        val changed = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(updated.identity, changed.bootstrap.admission)
        assertEquals(HostAdmissionContract.DIRECT, changed.bootstrap.configSource)
        assertFalse(NoRootSupportStore.isRemoteSynced(context, updated.noRootRevision))

        assertTrue(NoRootSupportStore.disable(context))
        restartHost()
        val disabled = awaitHost { it.bootstrap.configState == HostConfigState.REJECTED }
        assertEquals(HostInstallChainState.NOT_STARTED, disabled.bootstrap.installChainState)
        assertEquals(0, disabled.bootstrap.hookPointInstalledCount)
        if (mode == NPatchMode.EMBEDDED) assertProcess(NPatchProcessState.DISABLED, mode)
        val settled = CountDownLatch(1)
        HostRuntimeProbeClient.probeAsync(context, verify = true) { settled.countDown() }
        assertTrue(settled.await(18, TimeUnit.SECONDS))
        assertEquals(NoRootSupportStore.SyncState.DISABLED, NoRootSupportStore.readStatus(context).syncState)

        // 留在已启用的修复后宿主，便于人工查看；不触发 Manager 镜像同步。
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertNotNull(PublicationAuthorityStore.current(context))
        restartHost()
        assertEquals(HostAdmissionContract.DIRECT,
            awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }.bootstrap.configSource)
        if (mode == NPatchMode.EMBEDDED) {
            assertProcess(NPatchProcessState.INSTALLED, mode)
            assertEquals(NPatchScopeProbe.ScopeState.UNKNOWN, NPatchScopeProbe.cached(context))
            assertFalse(NoRootSupportStore.readStatus(context).detail.orEmpty().startsWith("manager_cache_"))
            assertEmbeddedProcessScopes()
        }
    }

    private fun assertEmbeddedProcessScopes() {
        shell("am start -a android.intent.action.VIEW -d bilibili://browser?url=https%3A%2F%2Fwww.bilibili.com%2Fblackboard%2Fprivacy-h5.html -p tv.danmaku.bili")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var rows = emptyList<NPatchProcessSnapshot>()
        while (System.nanoTime() < deadline) {
            rows = NPatchProcessDiagnostics.query(context)
            val web = rows.singleOrNull { it.processName == "tv.danmaku.bili:web" }
            val child = rows.firstOrNull { it.processName in setOf("tv.danmaku.bili:download", "tv.danmaku.bili:ijkservice") &&
                it.state == NPatchProcessState.INSTALLED && it.installedHookCount > 0 }
            if (web?.state == NPatchProcessState.GATED && child != null) {
                assertEquals(0, web.installedHookCount)
                assertEquals(HostAdmissionContract.DIRECT, web.source)
                assertEquals(HostAdmissionContract.DIRECT, child.source)
                android.util.Log.i("BilibiliInnocentLab", "NPatch process acceptance: ${NPatchProcessCodec.encode(rows)}")
                return
            }
            Thread.sleep(500)
        }
        fail("Embedded process scopes not observed: $rows")
    }

    private fun assertProcess(state: NPatchProcessState, mode: NPatchMode) {
        // Binder 回执在 attach 中已可读，动态广播仍需等待各进程主线程完成宿主初始化。
        // 每次查询仍保持 1.5 秒边界；仅专项冷启动验收重试，不拿历史记录补成功。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var observed: NPatchProcessSnapshot? = null
        while (System.nanoTime() < deadline && observed == null) {
            observed = NPatchProcessDiagnostics.query(context).singleOrNull { it.processName == NoRootSupportState.TARGET_PACKAGE }
            if (observed == null) Thread.sleep(500)
        }
        assertNotNull("Current process did not respond after host initialization", observed)
        val row = observed!!
        assertEquals(mode, row.mode)
        assertEquals(state, row.state)
        if (state == NPatchProcessState.INSTALLED) {
            assertEquals(HostAdmissionContract.DIRECT, row.source)
            assertTrue(row.installedHookCount > 0)
        } else assertEquals(0, row.installedHookCount)
    }

    private fun restartHost() {
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
    }

    private fun awaitHost(accept: (HostRuntimeDiagnosticsSnapshot) -> Boolean): HostRuntimeDiagnosticsSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var last: HostRuntimeDiagnosticsQueryClient.Result? = null
        while (System.nanoTime() < deadline) {
            val done = CountDownLatch(1)
            val result = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { result.set(it); done.countDown() }
            if (done.await(4, TimeUnit.SECONDS)) {
                last = result.get()
                last?.snapshot?.takeIf(accept)?.let { return it }
            }
            Thread.sleep(500)
        }
        fail("Host did not reach expected state: ${last?.status}/${last?.failure}/${last?.snapshot?.bootstrap}")
        error("unreachable")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
