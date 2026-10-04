package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTopBarFxFeatureInstallerTest {

    private class Recorder : HookRegistrar by TestHookRegistrar {
        val ids = mutableListOf<String>()
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            ids += id
        }
    }

    private fun createEnv(
        processName: String = "tv.danmaku.bili",
        recorder: HookRegistrar = TestHookRegistrar,
        statusReporter: (String, String) -> Unit = { _, _ -> }
    ): HookEnvironment = HookEnvironment(
        processName = processName,
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader!!),
        registrar = recorder,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = statusReporter
    )

    @Test
    fun `skips installation when disabled`() {
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = false,
            touchGlow = false,
            points = null
        )
        val result = installer.install(createEnv())
        assertTrue(result is FeatureInstallResult.Skipped)
        assertEquals("disabled", (result as FeatureInstallResult.Skipped).reason)
    }

    @Test
    fun `skips installation in non-main process`() {
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = null
        )
        val result = installer.install(createEnv(processName = "tv.danmaku.bili:play"))
        assertTrue(result is FeatureInstallResult.Skipped)
        assertEquals("non-main-process", (result as FeatureInstallResult.Skipped).reason)
    }

    @Test
    fun `reports missing point when baseOnViewCreated is absent`() {
        var channel = ""
        var status = ""
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = null
        )
        val result = installer.install(createEnv(statusReporter = { c, s -> channel = c; status = s }))
        assertTrue(result is FeatureInstallResult.Skipped)
        assertEquals("missing-view-created-point", (result as FeatureInstallResult.Skipped).reason)
        assertEquals(HostTopBarFxFeatureInstaller.CHANNEL_STATUS, channel)
        assertEquals("missing:missing-view-created-point", status)
    }

    @Test
    fun `successfully installs hook when baseOnViewCreated point is present`() {
        val recorder = Recorder()
        var reportedStatus = ""
        val point = VersionAdapter.HookPoint(
            className = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment",
            methodName = "onViewCreated",
            paramClassNames = null,
            viewField = "searchText"
        )
        val points = VersionAdapter.HomeTopBarPoints(
            gameMenu = null,
            composeGameMenu = null,
            baseOnViewCreated = point,
            defaultWordMethods = emptyList()
        )
        val installer = HostTopBarFxFeatureInstaller(
            liquidGlass = true,
            touchGlow = true,
            points = points
        )
        val result = installer.install(
            createEnv(recorder = recorder, statusReporter = { _, s -> reportedStatus = s })
        )
        assertTrue(result is FeatureInstallResult.Installed)
        assertEquals(1, (result as FeatureInstallResult.Installed).hookCount)
        assertEquals(listOf("host_top_bar_fx.view_created"), recorder.ids)
        assertEquals("success:active", reportedStatus)
    }

    @Test
    fun `isUnderlineIndicator identifies indicator characteristics and distinguishes dividers`() {
        open class MockView : View(null) {
            private var lp: ViewGroup.LayoutParams? = null
            override fun getLayoutParams(): ViewGroup.LayoutParams? = lp
            override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
                this.lp = params
            }
        }

        // 1. Indicator dimensions with small height/width identify indicator
        val indicatorView = object : MockView() {
            override fun getId(): Int = 0x7f090001
        }
        val indicatorLp = ViewGroup.LayoutParams(0, 0)
        indicatorLp.width = 60
        indicatorLp.height = 6
        indicatorView.setLayoutParams(indicatorLp)
        assertEquals(6, indicatorView.layoutParams?.height)
        assertTrue(HostTopBarFxController.isUnderlineIndicator(indicatorView))

        // 2. Full-width divider is NOT an underline indicator
        val dividerView = object : MockView() {
            override fun getId(): Int = 0x7f090002
        }
        val dividerLp = ViewGroup.LayoutParams(0, 0)
        dividerLp.width = 1080
        dividerLp.height = 2
        dividerView.setLayoutParams(dividerLp)
        assertFalse(HostTopBarFxController.isUnderlineIndicator(dividerView))
    }
}
