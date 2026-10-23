package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin
import org.junit.Assert.*
import org.junit.Test

class GuestOnboardingHooksTest {
    @Test fun skippingReleasesHomeLoadingWithoutSubmittingChoicesAndStopsAfterLogin() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        GuestOnboardingHooks { guest }.install(env)
        val plugin = NewUserInterestChoosePlugin()
        registrar.invoke("guest.onboarding.E", plugin) { plugin.E(); null }
        assertEquals(0, plugin.requests)
        assertEquals(1, plugin.completed)
        assertFalse(plugin.loading)
        assertFalse(plugin.submittedChoice)
        registrar.invoke("guest.onboarding.F", plugin, arrayOf(com.bilibili.pegasus.data.interestchoose.InterestChoose())) { plugin.F(null); null }
        assertEquals(0, plugin.dialogs)
        assertEquals(2, plugin.completed)
        assertFalse(plugin.submittedChoice)
        val low = com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin()
        registrar.invoke("guest.onboarding.low", low, arrayOf(com.bilibili.pegasus.data.interestchoose.InterestChoose())) { low.C(null); null }
        assertEquals(0, low.dialogs)
        assertFalse(low.loading)
        assertFalse(low.submittedChoice)
        guest = false
        registrar.invoke("guest.onboarding.E", plugin) { plugin.E(); null }
        assertEquals(1, plugin.requests)
        assertEquals(2, plugin.completed)
        registrar.invoke("guest.onboarding.low", low, arrayOf(com.bilibili.pegasus.data.interestchoose.InterestChoose())) { low.C(null); null }
        assertEquals(1, low.dialogs)
    }
}
