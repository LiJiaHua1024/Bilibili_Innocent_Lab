package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinDefaultWordsTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test

class KotlinDefaultWordsLocatorTest {
    @Suppress("UNUSED_PARAMETER")
    class Invalid {
        fun x(request: Any, encoder: SerializationStrategy<*>, decoder: DeserializationStrategy<*>,
            handler: KotlinDefaultWordsTest.Callback, proto: ProtoBuf): Any = "suspend"
    }
    private val first = KotlinDefaultWordsTest.RenamedSearch::class.java.declaredMethods.single { it.name == "x" }
    private val second = KotlinDefaultWordsTest.KotlinSearch::class.java.declaredMethods.single { it.name == "defaultWords" }

    @Test fun wrongAbiIsRejectedAndDuplicateEvidenceIsDeduplicated() {
        assertSame(first, KotlinDefaultWordsLocator.select(listOf(first, first)))
        assertNull(KotlinDefaultWordsLocator.select(Invalid::class.java.declaredMethods.toList()))
        assertNull(KotlinDefaultWordsLocator.select(listOf(first, second)))
        assertNull(KotlinDefaultWordsLocator.select(emptyList()))
    }

    @Test fun renamedCachePointResolvesAndIncompleteSignaturesFailClosed() {
        val loader = javaClass.classLoader!!
        val point = KotlinDefaultWordsLocator.point(first)
        assertEquals(first, KotlinDefaultWordsLocator.resolve(loader, point))
        assertNull(KotlinDefaultWordsLocator.resolve(loader, point.copy(paramClassNames = null)))
        assertNull(KotlinDefaultWordsLocator.resolve(loader, point.copy(methodName = "missing")))
    }

    @Test fun directHitsDisabledFeaturesAndUnsupportedHostsNeverRequestDex() {
        assertFalse(KotlinDefaultWordsLocator.needsQuery(false, true, false))
        assertFalse(KotlinDefaultWordsLocator.needsQuery(true, false, false))
        assertFalse(KotlinDefaultWordsLocator.needsQuery(true, true, true))
        assertTrue(KotlinDefaultWordsLocator.needsQuery(true, true, false))
    }

    @Test fun enablingAfterDisabledCacheRetriesOnceButCachedFailuresDoNotLoop() {
        assertTrue(KotlinDefaultWordsLocator.refreshCache(true, true, false, false, false))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(false, true, false, false, false))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(true, true, false, false, true))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(true, true, false, true, false))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(true, true, true, false, false))
        assertFalse(KotlinDefaultWordsLocator.refreshCache(true, false, false, false, false))
    }

    @Test fun adapterRecordsUnavailableAndAmbiguousResultsWithoutGuessing() {
        fun run(result: DexAssistResult) = VersionAdapter.locateKotlinDefaultWordsByDex(
            DexAssistSession(DexAssistEngine { result }, listOf("/base.apk"), javaClass.classLoader!!,
                setOf(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)))
        val found = run(DexAssistResult.Candidates(listOf(first)))
        assertEquals(KotlinDefaultWordsLocator.point(first), found.point)
        assertEquals(VersionAdapter.AdaptState.FOUND, found.diagnostic.state)
        for (result in listOf(DexAssistResult.Candidates(listOf(first, second)),
            DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED))) {
            val missing = run(result)
            assertNull(missing.point)
            assertEquals(VersionAdapter.AdaptState.MISSING, missing.diagnostic.state)
        }
        assertEquals(VersionAdapter.AdaptState.MISSING, VersionAdapter.locateKotlinDefaultWordsByDex(null).diagnostic.state)
    }

    @Test fun optionalPointRoundTripsAndOldJsonWithoutTheKeyIsAccepted() {
        val points = VersionAdapter.HomeTopBarPoints(null, null, null, emptyList(), KotlinDefaultWordsLocator.point(first))
        assertEquals(points, VersionAdapter.HomeTopBarPoints.fromJson(points.toJson()))
        val old = points.toJson().apply { remove("kotlin_words") }
        assertNull(VersionAdapter.HomeTopBarPoints.fromJson(old).kotlinDefaultWords)
    }
}
