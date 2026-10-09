package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Constructor

@RunWith(AndroidJUnit4::class)
class HostVideoCardSharedInstallerInstrumentedTest {
    private class Recorder(val failMeasurement: Boolean = false) : HookRegistrar {
        val ids = mutableListOf<String>()
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            ids += id
            if (failMeasurement && id.endsWith(".measure")) error("Simulated measurement hook failure")
        }
        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected first")
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected all")
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected adapted")
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) = error("Unexpected constructor")
    }
    private fun install(cards: Boolean, background: Boolean, recorder: Recorder): List<FeatureInstallRecord> {
        val loader = javaClass.classLoader!!
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), recorder,
            logInfo = { _, _ -> }, logError = { _, _ -> }, reportStatus = { _, _ -> })
        return FeatureInstallCoordinator(environment).installAll(HostVideoCardStyleFeatureInstaller.shared(cards, -1,
            HostBackgroundConfig(if (background) HostBackgroundPreset.AURORA else HostBackgroundPreset.OFF)))
    }

    @Test fun cardsAndBackgroundShareOneBindingHookWithIndependentCoverage() {
        val recorder = Recorder()
        val records = install(true, true, recorder)
        assertEquals(1, recorder.ids.count { it.endsWith(".bind") })
        assertEquals(1, recorder.ids.count { it.endsWith(".measure") })
        assertEquals(FeatureInstallResult.Installed(2), records[0].result)
        assertEquals(FeatureInstallResult.Installed(1), records[1].result)
    }
    @Test fun backgroundAloneDoesNotInstallCardMeasurements() {
        val recorder = Recorder()
        val records = install(false, true, recorder)
        assertEquals(listOf("host_background.bind"), recorder.ids)
        assertEquals(FeatureInstallResult.Skipped("disabled"), records[0].result)
        assertEquals(FeatureInstallResult.Installed(1), records[1].result)
    }
    @Test fun disabledBackgroundKeepsItsIndependentDiagnosticState() {
        val recorder = Recorder()
        val records = install(true, false, recorder)
        assertEquals(1, recorder.ids.count { it.endsWith(".bind") })
        assertEquals(FeatureInstallResult.Skipped("disabled"), records[1].result)
        val bothOff = Recorder()
        assertTrue(install(false, false, bothOff).all { it.result == FeatureInstallResult.Skipped("disabled") })
        assertTrue(bothOff.ids.isEmpty())
    }
    @Test fun failedCardMeasurementDoesNotDisableBackground() {
        val recorder = Recorder(failMeasurement = true)
        val records = install(true, true, recorder)
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), records[0].result)
        assertEquals(FeatureInstallResult.Installed(1), records[1].result)
        assertEquals(1, recorder.ids.count { it.endsWith(".bind") })
    }
}
