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
        assertEquals(NPatchMode.MANAGER, NPatchRuntimeProfiles.read(context).mode)
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
