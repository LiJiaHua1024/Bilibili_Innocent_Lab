package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FramePreparationRetryTest {
    @Test fun transientLoadFailuresRetryWithoutAnExistingBackendButNeverBeforeTheDeadline() {
        val retry = FramePreparationRetry(10, 2, 100)
        retry.failed(FramePreparationRetry.Work.LOAD, 0)
        assertNull(retry.take(9, false))
        assertEquals(FramePreparationRetry.Work.LOAD, retry.take(10, false))
        assertNull(retry.take(11, true))
        retry.failed(FramePreparationRetry.Work.LOAD, 12)
        assertEquals(FramePreparationRetry.Work.LOAD, retry.take(22, false))
        retry.failed(FramePreparationRetry.Work.LOAD, 23)
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, false))
    }

    @Test fun newPlaybackRearmsAnExhaustedBudgetOnlyAfterAQuietWindow() {
        val retry = FramePreparationRetry(10, 1, 100)
        retry.failed(FramePreparationRetry.Work.CAPABILITY, 0)
        assertEquals(FramePreparationRetry.Work.CAPABILITY, retry.take(10, false))
        retry.failed(FramePreparationRetry.Work.CAPABILITY, 11)
        assertNull(retry.take(109, true))
        assertEquals(FramePreparationRetry.Work.CAPABILITY, retry.take(110, true))
    }

    @Test fun unsupportedAbiOrRestartRequiredHasNoAutomaticOrPlaybackRetry() {
        val retry = FramePreparationRetry(10, 3, 100)
        retry.failed(null, 0)
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, true))
    }

    @Test fun successfulPreparationRevokesPendingRetries() {
        val retry = FramePreparationRetry(10, 3, 100)
        retry.failed(FramePreparationRetry.Work.LOAD, 0)
        retry.ready()
        assertFalse(retry.canSchedule())
        assertNull(retry.take(1000, true))
    }
}
