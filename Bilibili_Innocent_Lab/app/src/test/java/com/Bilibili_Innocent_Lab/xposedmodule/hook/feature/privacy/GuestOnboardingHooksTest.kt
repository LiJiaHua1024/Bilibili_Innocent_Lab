package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin
import com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin
import com.bilibili.pegasus.data.interestchoose.InterestChoose
import org.junit.Assert.*
import org.junit.Test

class GuestOnboardingHooksTest {
    @Test fun bothPluginsAreFinishedForGuestsByShapeWithoutSubmittingChoicesAndPassThroughAfterLogin() {
        var guest = true
        val registrar = PlayerPortTestRegistrar()
        val loader = javaClass.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        // Both plugins are located by ABI shape (show = (InterestChoose):void, finish = (boolean, ref[, boolean]):void).
        assertEquals(2, GuestOnboardingHooks { guest }.install(env))

        val plugin = NewUserInterestChoosePlugin()
        registrar.invoke("guest.onboarding.new.show", plugin, arrayOf(InterestChoose())) { plugin.F(InterestChoose()); null }
        assertEquals(0, plugin.dialogs)      // wizard not shown
        assertEquals(1, plugin.completed)    // finished instead, releasing the loading gate
        assertFalse(plugin.loading)
        assertFalse(plugin.submittedChoice)  // finish(false, null) submits nothing

        val low = LowActiveInterestChoosePlugin()
        registrar.invoke("guest.onboarding.low.show", low, arrayOf(InterestChoose())) { low.C(InterestChoose()); null }
        assertEquals(0, low.dialogs)
        assertFalse(low.loading)
        assertFalse(low.submittedChoice)     // finish(false, null, false) submits nothing

        // Once logged in, the real show runs untouched.
        guest = false
        registrar.invoke("guest.onboarding.new.show", plugin, arrayOf(InterestChoose())) { plugin.F(InterestChoose()); null }
        assertEquals(1, plugin.dialogs)
        registrar.invoke("guest.onboarding.low.show", low, arrayOf(InterestChoose())) { low.C(InterestChoose()); null }
        assertEquals(1, low.dialogs)
    }
}
