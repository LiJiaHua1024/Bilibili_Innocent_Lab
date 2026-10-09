package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.MotionEvent
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test

class HostTouchGlowFeatureInstallerTest {
    @Test fun observesGroupDispatchOnlyWhenStandaloneGlowIsEnabledInTheMainProcess() {
        val registrations = mutableListOf<String>()
        val registrar = object : HookRegistrar by TestHookRegistrar {
            override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>,
                block: ModernMemberHookCreator.() -> Unit) {
                assertEquals(ViewGroup::class.java, owner)
                assertEquals("dispatchTouchEvent", methodName)
                assertEquals(listOf(MotionEvent::class.java), parameterTypes.toList())
                registrations += id
            }
        }
        fun env(process: String) = HookEnvironment(process, javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader!!), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertEquals(FeatureInstallResult.Skipped("disabled"), HostTouchGlowFeatureInstaller(false).install(env("tv.danmaku.bili")))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), HostTouchGlowFeatureInstaller(true).install(env("tv.danmaku.bili:play")))
        assertTrue(registrations.isEmpty())
        assertEquals(FeatureInstallResult.Installed(1), HostTouchGlowFeatureInstaller(true).install(env("tv.danmaku.bili")))
        assertEquals(listOf("host_touch_glow.dispatch"), registrations)
    }
}
