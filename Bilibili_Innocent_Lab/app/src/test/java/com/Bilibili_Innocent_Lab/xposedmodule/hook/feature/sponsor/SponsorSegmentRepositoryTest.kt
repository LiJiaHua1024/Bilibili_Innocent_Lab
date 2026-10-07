package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SponsorSegmentRepositoryTest {
    private val video = SponsorVideoId("BV14741127BN", 168885122)
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(3, TimeUnit.SECONDS)) }

    @Test fun concurrentConsumersShareOneFetchAndAnAvailableResultIsCached() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(2)
        val calls = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); entered.countDown(); await(release); SponsorFetchResult.Available(emptyList())
        }).use { repo ->
            repo.lookup(video) { delivered.countDown() }; await(entered)
            repo.lookup(video) { delivered.countDown() }; release.countDown(); await(delivered)
            val cached = CountDownLatch(1); repo.lookup(video) { cached.countDown() }; await(cached)
            assertEquals(1, calls.get())
        }
    }
    @Test fun cancellingOneConsumerDoesNotCancelTheOther() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val delivered = CountDownLatch(1)
        val cancelledDeliveries = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            entered.countDown(); await(release); SponsorFetchResult.Available(emptyList())
        }).use { repo ->
            val ticket = repo.lookup(video) { cancelledDeliveries.incrementAndGet() }; await(entered)
            repo.lookup(video) { delivered.countDown() }; ticket.cancel(); release.countDown(); await(delivered)
            assertEquals(0, cancelledDeliveries.get())
        }
    }
    @Test fun hardDeadlineReportsFailureAndLateCompletionCannotReplaceANewFlight() {
        val firstEntered = CountDownLatch(1); val releaseOld = CountDownLatch(1); val oldDone = CountDownLatch(1)
        val expired = CountDownLatch(1); val secondDelivered = CountDownLatch(1); val calls = AtomicInteger()
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                while (releaseOld.count > 0) try { releaseOld.await() } catch (_: InterruptedException) { }
                oldDone.countDown(); SponsorFetchResult.Available(listOf(SponsorSegment("old", 1, 2, 0)))
            } else SponsorFetchResult.Available(listOf(SponsorSegment("new", 1, 2, 0)))
        }, timeoutMs = 100).use { repo ->
            repo.lookup(video) { assertTrue(it is SponsorFetchResult.Unavailable); expired.countDown() }
            await(firstEntered); await(expired)
            repo.lookup(video) { assertEquals("new", (it as SponsorFetchResult.Available).segments.single().uuid); secondDelivered.countDown() }
            await(secondDelivered); releaseOld.countDown(); await(oldDone)
            val checked = CountDownLatch(1)
            repo.lookup(video) { assertEquals("new", (it as SponsorFetchResult.Available).segments.single().uuid); checked.countDown() }
            await(checked)
        }
    }
    @Test fun unavailableResultsAreNotCachedAndCloseRejectsNewWork() {
        val calls = AtomicInteger(); val repo = SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); SponsorFetchResult.Unavailable("offline")
        })
        repeat(2) { val done = CountDownLatch(1); repo.lookup(video) { done.countDown() }; await(done) }
        assertEquals(2, calls.get()); repo.close()
        var reason = ""; repo.lookup(video) { reason = (it as SponsorFetchResult.Unavailable).reason }
        assertEquals("closed", reason)
    }
    @Test fun serverRateLimitBacksOffAcrossVideosWithoutDiscardingCachedData() {
        val calls = AtomicInteger(); var clock = 0L
        SponsorSegmentRepository(SponsorSegmentSource { _, _ ->
            calls.incrementAndGet(); SponsorFetchResult.Unavailable("rate-limited")
        }, now = { clock }).use { repo ->
            val done = CountDownLatch(1); repo.lookup(video) { done.countDown() }; await(done)
            repo.lookup(video.copy(cid = 2)) { assertEquals("rate-limited", (it as SponsorFetchResult.Unavailable).reason) }
            assertEquals(1, calls.get())
            clock = 60_001
            val retry = CountDownLatch(1); repo.lookup(video) { retry.countDown() }; await(retry)
            assertEquals(2, calls.get())
        }
    }
}
