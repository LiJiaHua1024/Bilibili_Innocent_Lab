package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

/** Skip both automatic requests and cached onboarding presentation, without changing host preferences. */
internal class GuestOnboardingHooks(private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment): Int {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val plugin = host.type("com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin")
        val starts = listOf(
            requireNotNull(Lookup.methodOrNull(plugin, "E")),
            requireNotNull(Lookup.methodOrNull(plugin, "F", host.type("com.bilibili.pegasus.data.interestchoose.InterestChoose")))
        )
        val finish = requireNotNull(Lookup.methodOrNull(plugin, "B", classOf<Boolean>(), host.type("bn3.h")))
        starts.forEach { start -> env.registrar.exact("guest.onboarding.${start.name}", plugin, start.name, *start.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val target = instance ?: return@before
                result = null
                // 8.90.2 uses B(false, null) when no choice is available. It releases the in-memory
                // homepage loading gate and loads the normal feed. Returning from E alone strands it.
                // No synthetic gender/age/interests, account write, or persisted 'completed' flag.
                finish.invoke(target, false, null)
                env.reportStatus("guest_onboarding", "skipped")
            }
        }
        }
        // The homepage response can use the separate low-activity plugin even for a fresh guest.
        // Its C() method also shows the gender/age/interest wizard. A(false, null, false) is
        // the verified no-choice fallback and resumes the normal feed without a selection payload.
        val low = host.type("com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin")
        val model = host.type("com.bilibili.pegasus.data.interestchoose.InterestChoose")
        val lowStart = requireNotNull(Lookup.methodOrNull(low, "C", model))
        val lowFinish = requireNotNull(Lookup.methodOrNull(low, "A", classOf<Boolean>(), host.type("bn3.h"), classOf<Boolean>()))
        env.registrar.exact("guest.onboarding.low", low, lowStart.name, *lowStart.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val target = instance ?: return@before
                result = null
                lowFinish.invoke(target, false, null, false)
                env.reportStatus("guest_onboarding", "skipped-low")
            }
        }
        return starts.size + 1
    }
}
