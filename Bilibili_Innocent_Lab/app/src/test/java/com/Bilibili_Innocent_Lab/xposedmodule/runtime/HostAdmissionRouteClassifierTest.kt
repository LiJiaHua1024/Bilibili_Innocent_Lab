package com.Bilibili_Innocent_Lab.xposedmodule.runtime
import org.junit.Assert.*
import org.junit.Test

class HostAdmissionRouteClassifierTest {
    @Test fun disabledRevisionRequiresABoundExplicitCloseResponse() {
        assertEquals(42L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", false, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("denied", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("stale", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("granted", true, 42L))
        assertEquals(0L, HostAdmissionRouteClassifier.disabledRevision("manager_sync_required", true, -1L))
    }
    @Test fun npatchRequiresFreshAdmissionWhileStandardFrameworkKeepsValidatedFallback() {
        fun allowed(npatch: Boolean) = HostAdmissionRouteClassifier.allowRemoteConfigFallback(
            false, null, true, true, 1L, nPatchRuntime = npatch)
        assertFalse(allowed(true))
        assertTrue(allowed(false))
    }
    @Test fun endpointStatusesAreClassifiedExhaustively() {
        val expected = mapOf(
            "prepared" to HostAdmissionRouteClassifier.StatusClass.PREPARED,
            "granted" to HostAdmissionRouteClassifier.StatusClass.GRANTED,
            "denied" to HostAdmissionRouteClassifier.StatusClass.AUTHORITY_DENIED,
            "consent_commit_failed" to HostAdmissionRouteClassifier.StatusClass.AUTHORITY_DENIED,
            "manager_sync_required" to HostAdmissionRouteClassifier.StatusClass.AUTHORITY_DENIED,
            "identity_rejected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "protocol_rejected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "storage_failed" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "stale" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "busy" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            null to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
            "unexpected" to HostAdmissionRouteClassifier.StatusClass.UNAVAILABLE,
        )
        expected.forEach { (status, kind) ->
            assertEquals(status, kind, HostAdmissionRouteClassifier.classify(status))
        }
    }

    @Test fun identityRejectedWithEmptyNonceIsUnavailableNotDenied() {
        val outcome = HostAdmissionRouteClassifier.prepare("identity_rejected", envelopeValid = false)
        assertEquals(HostAdmissionRouteClassifier.Outcome.UNAVAILABLE, outcome)
        assertEquals(
            HostAdmissionRouteClassifier.REASON_UNAVAILABLE,
            HostAdmissionRouteClassifier.reason(outcome)
        )
        assertFalse(HostAdmissionRouteClassifier.latchesDenial("admission_timeout"))
        assertFalse(HostAdmissionRouteClassifier.latchesDenial("admission_unavailable"))
        assertFalse(HostAdmissionRouteClassifier.latchesDenial(""))
    }

    @Test fun termsDeniedAndManagerSyncLatchFallback() {
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.prepare("denied", envelopeValid = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.prepare("consent_commit_failed", envelopeValid = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.AUTHORITY_DENIED,
            HostAdmissionRouteClassifier.prepare("manager_sync_required", envelopeValid = true)
        )
        assertTrue(HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_DENIED))
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = true,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
    }

    @Test fun transportTimeoutWithValidRemotePrefAllowsFallback() {
        assertTrue(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = "remote_group_unavailable",
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L,
            )
        )
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = null,
                authorized = true,
                moduleVersionMatches = false,
                generation = 1L,
            )
        )
    }

    @Test fun malformedEnvelopeIsUnavailableSoOtherRoutesCanContinue() {
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.prepare("prepared", envelopeValid = false)
        )
        assertFalse(
            HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_UNAVAILABLE)
        )
    }

    @Test fun confirmGrantedRequiresBindingButDoesNotLatchDenial() {
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.CONTINUE,
            HostAdmissionRouteClassifier.confirm("granted", successBinding = true)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("granted", successBinding = false)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("identity_rejected", successBinding = false)
        )
        assertEquals(
            HostAdmissionRouteClassifier.Outcome.UNAVAILABLE,
            HostAdmissionRouteClassifier.confirm("stale", successBinding = false)
        )
        assertFalse(HostAdmissionRouteClassifier.latchesDenial(HostAdmissionRouteClassifier.REASON_UNAVAILABLE))
    }
}
