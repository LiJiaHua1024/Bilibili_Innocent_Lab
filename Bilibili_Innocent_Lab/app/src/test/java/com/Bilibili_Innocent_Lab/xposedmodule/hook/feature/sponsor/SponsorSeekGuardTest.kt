package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorSeekGuardTest {
    @Test fun queuedCloneIsRecheckedAtExecutionAfterAVideoSwitch() {
        val guard = SponsorSeekGuard(); val owner = Any(); val original = Any(); val clone = Any()
        var current = true; var executions = 0
        assertTrue(guard.submit(owner, 20, { current }, { executions++ }) { guard.capture(original, owner, 20) })
        guard.carry(original, clone)
        current = false
        assertFalse(guard.allow(clone))
        assertEquals(0, executions)
    }
    @Test fun userAndOtherPlayerCoroutinesRemainUntouched() {
        val guard = SponsorSeekGuard(); val owner = Any(); val user = Any()
        assertTrue(guard.allow(user))
        assertFalse(guard.submit(owner, 20, { true }, {}) { guard.capture(user, Any(), 20) })
        assertTrue(guard.allow(user))
    }
    @Test fun acceptedRequestIsMarkedOnceAndRejectedScopeNeverInvokesTheHost() {
        val guard = SponsorSeekGuard(); val owner = Any(); val coroutine = Any(); var count = 0
        assertFalse(guard.submit(owner, 20, { false }, {}) { fail("must not invoke") })
        assertTrue(guard.submit(owner, 20, { true }, { count++ }) { guard.capture(coroutine, owner, 20) })
        assertTrue(guard.allow(coroutine)); assertTrue(guard.allow(coroutine))
        assertEquals(1, count)
    }
}
