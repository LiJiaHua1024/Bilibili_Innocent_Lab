package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.view.View
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.AmbiguousCastRepository
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.CastClickLambda
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.CastClickLambdaNoPayload
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.CastClickLambdaNotListener
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.CastClickLambdaStringPayload
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.CastClickLambdaTyped
import com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.ToolbarCastScreenRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastToolbarClickLocatorTest {
    private val loader = javaClass.classLoader!!
    private val repository = ToolbarCastScreenRepository::class.java

    @Test
    fun staticCallbackIsResolvedWithExactSignature() {
        val point = requireNotNull(CastToolbarClickLocator.direct(loader))
        assertEquals(CastToolbarClickLocator.StaticCallback::class.java, point.javaClass)
        assertEquals(repository, point.owner)
        assertEquals("f", point.method.name)
        assertTrue(java.lang.reflect.Modifier.isStatic(point.method.modifiers))
        assertEquals(listOf(repository, View::class.java), point.method.parameterTypes.toList())
    }

    @Test
    fun ambiguousStaticCallbacksAreRejected() {
        val mapped = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == CastToolbarClickLocator.REPOSITORY) return AmbiguousCastRepository::class.java
                return super.loadClass(name, resolve)
            }
        }
        assertNull(CastToolbarClickLocator.direct(mapped))
    }

    @Test
    fun mergedLambdaIsAcceptedOnlyWithSelectorAndPayload() {
        val good = CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambda::class.java.name, true))
        assertTrue(good is CastToolbarClickLocator.LambdaListener)
        assertEquals(CastClickLambda::class.java, requireNotNull(good).owner)

        assertNull(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambdaNoPayload::class.java.name, true)))
        assertNull(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambdaStringPayload::class.java.name, true)))
        // 接口归属不是硬条件：同 ABI + 捕获字段的类仍被接受，是否真被点击链路调用由宿主决定。
        assertTrue(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambdaNotListener::class.java.name, true))
            is CastToolbarClickLocator.LambdaListener)
        assertNull(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint("com.bilibili.ship.theseus.missing.Owner", true)))
    }

    @Test
    fun singlePurposeLambdaWithTypedRepositoryFieldIsAccepted() {
        val point = requireNotNull(
            CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambdaTyped::class.java.name, true)))
        assertTrue(point is CastToolbarClickLocator.LambdaListener)
        val repositoryInstance = ToolbarCastScreenRepository()
        assertTrue(CastToolbarClickLocator.matches(CastClickLambdaTyped(repositoryInstance), point))
    }

    @Test
    fun cachedEntryIsReverifiedAgainstTheCurrentClassLoader() {
        val point = requireNotNull(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(repository.name, false)))
        assertEquals(repository, point.owner)
        val missing = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == CastToolbarClickLocator.REPOSITORY) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(CastToolbarClickLocator.direct(missing))
        assertNull(CastToolbarClickLocator.cached(missing, CastToolbarClickPoint(repository.name, false)))
        assertFalse(CastToolbarClickLocator.applicable(missing))
    }

    @Test
    fun payloadIdentityDecidesTheMergedLambdaBranch() {
        val point = requireNotNull(CastToolbarClickLocator.cached(loader, CastToolbarClickPoint(CastClickLambda::class.java.name, true)))
        val cast = CastClickLambda(1, repository.getDeclaredConstructor().newInstance())
        val other = CastClickLambda(1, "unrelated")
        assertTrue(CastToolbarClickLocator.matches(cast, point))
        assertFalse(CastToolbarClickLocator.matches(other, point))
        assertFalse(CastToolbarClickLocator.matches(null, point))

        val static = requireNotNull(CastToolbarClickLocator.direct(loader))
        assertTrue(CastToolbarClickLocator.matches(Any(), static))
        assertFalse(CastToolbarClickLocator.matches(null, static))
    }

    @Test
    fun queryIsPlannedOnlyWhenDirectEntryIsMissing() {
        assertFalse(CastToolbarClickLocator.needsQuery(loader, enabled = false, directFound = false))
        assertFalse(CastToolbarClickLocator.needsQuery(loader, enabled = true, directFound = true))
        assertTrue(CastToolbarClickLocator.needsQuery(loader, enabled = true, directFound = false))
    }

    @Test
    fun cachedPointRoundTripsThroughJson() {
        val value = CastToolbarClickPoint(CastClickLambda::class.java.name, true)
        val decoded = requireNotNull(CastToolbarClickPoint.fromJson(value.toJson()))
        assertEquals(value, decoded)
        val merged = CastToolbarClickPoint("Bc.c", true)
        assertEquals(merged, CastToolbarClickPoint.fromJson(merged.toJson()))
        assertNull(CastToolbarClickPoint.fromJson(org.json.JSONObject().put("owner", "")))
        assertNull(CastToolbarClickPoint.fromJson(org.json.JSONObject().put("owner", "com.other/R repository")))
    }
}
