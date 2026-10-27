package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.lang.reflect.Method

/**
 * Skip the new-user / low-activity interest-choose onboarding for guests, without changing host
 * preferences, submitting a choice, or persisting a "completed" flag.
 *
 * The host obfuscates the plugin method names per build and, since 8.97, moved parts of the flow
 * into coroutines, so the old by-name anchors (E/F/B/C/A + the obfuscated `bn3.h` type) only ever
 * resolved on 8.90.2. These classes are instead anchored by their version-stable ABI shape (matrix
 * `d10de235`/`664d459e`, 38 builds):
 *  - show   = the SINGLE `(InterestChoose):void` method (`tryShowInterestDialog`), unique 38/38.
 *  - finish = the SINGLE `(boolean, <ref>):void` method (New) / `(boolean, <ref>, boolean):void`
 *             method (Low); `finish(false, null[, false])` resets the home loading gate and loads
 *             the normal feed with no choice payload (confirmed by the 9.16.1 decompile of New.c).
 * Each plugin installs independently and fails CLOSED: an absent or ambiguous shape (e.g. the Low
 * show method is a `(InterestChoose, Continuation)` coroutine on 8.97+) is skipped, never guessed,
 * and never strands the loading gate.
 */
internal class GuestOnboardingHooks(private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment): Int {
        val loader = requireNotNull(env.classLoader)
        val model = PrivacyStructuralResolver.type(loader, "com.bilibili.pegasus.data.interestchoose.InterestChoose")
            ?: return 0
        var installed = 0
        installed += installPlugin(env, loader, model,
            "com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin", finishTrailingFalse = false,
            status = "skipped", id = "guest.onboarding.new")
        installed += installPlugin(env, loader, model,
            "com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin", finishTrailingFalse = true,
            status = "skipped-low", id = "guest.onboarding.low")
        return installed
    }

    /** @param finishTrailingFalse Low's finish takes a trailing realtime flag; New's does not. */
    private fun installPlugin(
        env: HookEnvironment, loader: ClassLoader, model: Class<*>,
        className: String, finishTrailingFalse: Boolean, status: String, id: String
    ): Int = runCatching {
        val plugin = PrivacyStructuralResolver.type(loader, className) ?: return@runCatching 0
        val show = PrivacyStructuralResolver.uniqueMethod(plugin) { m ->
            m.returnType == Void.TYPE && m.parameterTypes.toList() == listOf(model)
        } ?: return@runCatching 0
        val finishShape: (Method) -> Boolean = { m ->
            m.returnType == Void.TYPE && isRefParam(m, model) &&
                if (finishTrailingFalse) {
                    m.parameterCount == 3 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes[2] == Boolean::class.javaPrimitiveType
                } else {
                    m.parameterCount == 2 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
        }
        val finish = PrivacyStructuralResolver.uniqueMethod(plugin, predicate = finishShape) ?: return@runCatching 0
        val args: Array<Any?> = if (finishTrailingFalse) arrayOf(false, null, false) else arrayOf(false, null)
        env.registrar.exact("$id.show", plugin, show.name, *show.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val target = instance ?: return@before
                result = null
                finish.invoke(target, *args)
                env.reportStatus("guest_onboarding", status)
            }
        }
        1
    }.getOrElse {
        env.logError("${id.replace('.', '_')}", "[BIL] Guest onboarding adapter unavailable: ${it.javaClass.simpleName}")
        0
    }

    /** The finish method carries exactly one reference parameter (the choice/callback), never the model itself. */
    private fun isRefParam(m: Method, model: Class<*>): Boolean {
        val refs = m.parameterTypes.filter { !it.isPrimitive }
        return refs.size == 1 && refs[0] != model
    }
}
