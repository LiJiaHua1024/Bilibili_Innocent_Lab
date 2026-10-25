package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import java.util.concurrent.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.*
import org.junit.Test

class BrandSplash917BehaviorTest {
    interface State
    interface MutableState : State
    class Exit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customBitmapReady$delegate`: MutableState? = null
        @JvmField var `$customLogoReady$delegate`: MutableState? = null
        @JvmField var `$currentOnShow`: State? = null
        fun invokeSuspend(value: Any?): Any? = value
    }
    private class Host : ClassLoader(BrandSplash917BehaviorTest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            BrandSplashSkipFeatureInstaller.KNTR_EXIT -> throw ClassNotFoundException(name)
            BrandSplashKntrAccess.CURRENT_EXIT -> Exit::class.java
            "androidx.compose.runtime.MutableState" -> MutableState::class.java
            "androidx.compose.runtime.State" -> State::class.java
            else -> super.loadClass(name, resolve)
        }
    }
    private fun installed(mainThread: Boolean = true): Pair<PlayerPortTestRegistrar, BrandSplashKntrExit> {
        val loader = Host()
        val point = requireNotNull(BrandSplashKntrAccess.exit(loader))
        assertTrue(point.waitsForImages)
        val registrar = PlayerPortTestRegistrar()
        val environment = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertTrue(BrandSplashSkipFeatureInstaller(true) { mainThread }.install(environment) is FeatureInstallResult.Installed)
        return registrar to point
    }

    @Test fun `waiting exit preserves native show persistence and the suspended result`() {
        val (registrar, _) = installed()
        val events = arrayListOf<String>()
        val frame = Exit { events += "exit" }
        val result = registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) {
            events += "show"; events += "persist"; frame.label = 1; COROUTINE_SUSPENDED
        }
        assertSame(COROUTINE_SUSPENDED, result)
        assertEquals(listOf("show", "persist", "exit"), events)
        assertEquals(1, frame.label)
        var resumed = 0
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { resumed++; Unit })
        assertEquals(0, resumed)
        assertEquals(3, events.size)
    }

    @Test fun `readiness return does not exit or advance photo rotation`() {
        val (registrar, point) = installed()
        var exits = 0
        val frame = Exit { exits++ }
        assertSame(Unit, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { Unit })
        assertEquals(0, exits)
        assertFalse(point.exited(frame, Unit))
        frame.label = 1
        assertTrue(point.exited(frame, Unit))
        assertFalse(point.exited(frame, COROUTINE_SUSPENDED))
    }

    @Test fun `cancelled pending completion reaches the native failure path`() {
        val (registrar, _) = installed()
        var exits = 0
        val frame = Exit { exits++ }
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { frame.label = 1; COROUTINE_SUSPENDED }
        val cancelled = CancellationException("fixture cancellation")
        val failure = Class.forName("kotlin.Result\$Failure").getConstructor(Throwable::class.java).newInstance(cancelled)
        val actual = runCatching {
            registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(failure)) {
                assertEquals(1, frame.label)
                throw cancelled
            }
        }.exceptionOrNull()
        assertSame(cancelled, actual)
        assertEquals(1, exits)
    }

    @Test fun `failed early callback keeps native timer and waiting label`() {
        val (registrar, _) = installed()
        val frame = Exit { error("fixture callback unavailable") }
        assertSame(COROUTINE_SUSPENDED, registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) {
            frame.label = 1; COROUTINE_SUSPENDED
        })
        assertEquals(1, frame.label)
    }

    @Test fun `off main completion retains the original wait`() {
        val (registrar, _) = installed(mainThread = false)
        var exits = 0
        val frame = Exit { exits++ }
        registrar.invoke("brand.skip.kntr.exit", frame, arrayOf(Unit)) { frame.label = 1; COROUTINE_SUSPENDED }
        assertEquals(1, frame.label)
        assertEquals(0, exits)
    }

    @Test fun `page ABI preserves the additional native onShow argument`() {
        val prefix = listOf("brand.Model", "kotlin.jvm.functions.Function0", "androidx.compose.ui.Modifier", "java.lang.String")
        assertEquals(BrandSplashPageAbi(4, 5), brandSplashPageAbi(prefix + listOf("androidx.compose.runtime.Composer", "int")))
        assertEquals(BrandSplashPageAbi(4, 5), brandSplashPageAbi(prefix + listOf("androidx.compose.runtime.Composer", "int", "int")))
        assertEquals(BrandSplashPageAbi(5, 6), brandSplashPageAbi(prefix + listOf("kotlin.jvm.functions.Function0", "androidx.compose.runtime.Composer", "int")))
        assertNull(brandSplashPageAbi(prefix + listOf("java.lang.Object", "androidx.compose.runtime.Composer", "int")))
        assertNull(brandSplashPageAbi(prefix + listOf("kotlin.jvm.functions.Function0", "int", "androidx.compose.runtime.Composer")))
    }
}
