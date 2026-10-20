package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class HostAdmissionCollectTest {
    @Test fun aGenericDenialCannotEraseTheCurrentCloseRevision() {
        val closed = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED, disabledRevision = 42L)
        val first = HostAdmissionCollect().accept(closed, acceptGrant = true).accept(denied(), acceptGrant = true)
        val second = HostAdmissionCollect().accept(denied(), acceptGrant = true).accept(closed, acceptGrant = true)
        assertEquals(42L, first.failure.disabledRevision)
        assertEquals(42L, second.failure.disabledRevision)
        assertNull(first.grant)
        assertNull(second.grant)
    }
    private fun unavailable() = HostAdmissionClient.Result()
    private fun denied() = HostAdmissionClient.Result(reason = HostAdmissionRouteClassifier.REASON_DENIED)
    private fun timeout() = HostAdmissionClient.Result(reason = "admission_timeout")
    private fun granted(): HostAdmissionClient.Result {
        val snapshot = RemoteHookConfigSnapshot(
            generation = 1L,
            moduleVersionCode = 1L,
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = emptyMap(),
        )
        return HostAdmissionClient.Result(
            HostAdmissionClient.Grant(snapshot, "remote_config_transport_fallback"),
            reason = "",
        )
    }

    @Test fun firstGrantWinsInsideTheWindow() {
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertNotNull(collect.grant)
        assertFalse(collect.sawDenied)
    }

    @Test fun authorityDenialDiscardsALaterGrant() {
        val collect = HostAdmissionCollect()
            .accept(denied(), acceptGrant = true)
            .accept(granted(), acceptGrant = true)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_DENIED, collect.failure.reason)
    }

    @Test fun timeoutDrainLatchesADenyThatAlreadyArrived() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(denied())
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(timeout(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertTrue(collect.sawDenied)
        assertNull(collect.grant)
        assertTrue(queue.isEmpty())
    }

    @Test fun timeoutDrainIgnoresLateGrantsWhenNobodyDenied() {
        val queue = LinkedBlockingQueue<HostAdmissionClient.Result>()
        queue.offer(granted())
        val collect = HostAdmissionCollect()
            .accept(unavailable(), acceptGrant = true)
            .drain(queue, acceptGrant = false)
        assertFalse(collect.sawDenied)
        assertNull(collect.grant)
        assertNull(collect.failure.grant)
        assertEquals(HostAdmissionRouteClassifier.REASON_UNAVAILABLE, collect.failure.reason)
    }
}
