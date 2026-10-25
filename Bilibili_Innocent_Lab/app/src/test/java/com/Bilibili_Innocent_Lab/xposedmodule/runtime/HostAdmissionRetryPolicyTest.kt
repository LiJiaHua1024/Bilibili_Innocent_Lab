package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.*
import org.junit.Test

class HostAdmissionRetryPolicyTest {
    @Test fun repeatedForegroundFailuresHaveBackoffAndAFiniteAttemptBudget() {
        val policy = HostAdmissionRetryPolicy(0L)
        var now = 0L
        repeat(HostAdmissionRetryPolicy.MAX_ATTEMPTS) {
            val delay = checkNotNull(policy.nextDelay(now))
            if (it > 0) assertTrue(delay > 0L)
            now += delay
            assertNotNull(policy.begin(now))
        }
        assertNull(policy.nextDelay(now))
        assertNull(policy.begin(now))
    }

    @Test fun pauseInvalidatesOldResultsAndAFreshForegroundAttemptCanRecover() {
        val policy = HostAdmissionRetryPolicy(0L)
        val old = checkNotNull(policy.begin(0L))
        policy.invalidate()
        assertFalse(policy.accepts(old, 1L))
        val current = checkNotNull(policy.begin(1L))
        assertTrue(policy.accepts(current, 2L))
        assertFalse(policy.accepts(old, 2L))
    }

    @Test fun expiryAndCloseRejectEvenASuccessfulPendingReply() {
        val policy = HostAdmissionRetryPolicy(0L)
        val token = checkNotNull(policy.begin(0L))
        assertFalse(policy.accepts(token, policy.deadline))
        assertNull(policy.nextDelay(policy.deadline))
        policy.close()
        assertFalse(policy.accepts(token, 1L))
        assertNull(policy.begin(1L))
    }

    @Test fun syncAndTransportFailuresAreRetryableButActualDenialIsNot() {
        for (reason in listOf(HostAdmissionRouteClassifier.REASON_SYNC_REQUIRED,
            HostAdmissionRouteClassifier.REASON_CONSENT_PENDING, HostAdmissionRouteClassifier.REASON_UNAVAILABLE,
            HostAdmissionRouteClassifier.REASON_RESPONSE_INVALID, HostAdmissionRouteClassifier.REASON_TIMEOUT)) {
            assertTrue(HostAdmissionRouteClassifier.retryable(reason))
        }
        assertFalse(HostAdmissionRouteClassifier.retryable(HostAdmissionRouteClassifier.REASON_DENIED))
        assertFalse(HostAdmissionRouteClassifier.retryable("unknown"))
    }

    @Test fun pauseAndCloseRemoveQueuedDeliveriesAndRejectFurtherPosting() {
        for (close in listOf(false, true)) {
            val queued = mutableListOf<Runnable>()
            val policy = HostAdmissionRetryPolicy(0L) { queued.remove(it) }
            val token = checkNotNull(policy.begin(0L))
            val task = Runnable { fail("cancelled delivery must not run") }
            assertTrue(policy.postDelivery(token, 0L, task) { queued.add(it) })
            if (close) policy.close() else policy.invalidate()
            assertTrue(queued.isEmpty())
            assertFalse(policy.takeDelivery(token, 1L, task))
            assertFalse(policy.postDelivery(token, 1L, task) { queued.add(it) })
            assertTrue(queued.isEmpty())
        }
    }

    @Test fun replacingADeliveryAndPostingFailuresCannotRetainAnOldResult() {
        val queued = mutableListOf<Runnable>()
        val policy = HostAdmissionRetryPolicy(0L) { queued.remove(it) }
        val token = checkNotNull(policy.begin(0L))
        val old = Runnable { }
        val current = Runnable { }
        assertTrue(policy.postDelivery(token, 0L, old) { queued.add(it) })
        assertTrue(policy.postDelivery(token, 0L, current) { queued.add(it) })
        assertEquals(listOf(current), queued)
        assertFalse(policy.takeDelivery(token, 1L, old))
        queued.remove(current)
        assertTrue(policy.takeDelivery(token, 1L, current))
        assertFalse(policy.takeDelivery(token, 1L, current))
        assertFalse(policy.postDelivery(token, 1L, old) { false })
        assertFalse(policy.takeDelivery(token, 1L, old))
        assertFalse(policy.postDelivery(token, 1L, old) { error("scheduler failed") })
        assertFalse(policy.takeDelivery(token, 1L, old))
    }

    @Test fun anExpiredQueuedSuccessCannotBeConsumed() {
        val policy = HostAdmissionRetryPolicy(0L)
        val token = checkNotNull(policy.begin(0L))
        val task = Runnable { }
        assertTrue(policy.postDelivery(token, 0L, task) { true })
        assertFalse(policy.takeDelivery(token, policy.deadline, task))
    }
}
