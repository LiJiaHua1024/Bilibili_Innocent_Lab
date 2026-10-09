package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorPlayerBindingsTest {
    @Test fun containerAssociationDoesNotRequireTheBadNetworkServiceOrAssumeTheForegroundPage() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any()
        val activity = Any(); val other = Any()
        bindings.wrapper(owner, wrapper, core); bindings.ready(owner, bindings.begin(owner))
        assertNull(bindings.select(activity))
        assertTrue(bindings.container(wrapper, activity))
        assertSame(owner, bindings.select(activity)!!.first)
        assertNull(bindings.select(other))
    }

    @Test fun contextAndWrapperEventsCanArriveInEitherOrder() {
        for (contextFirst in listOf(true, false)) {
            val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any(); val activity = Any()
            if (contextFirst) assertFalse(bindings.container(core, activity))
            bindings.wrapper(owner, wrapper, core)
            if (!contextFirst) assertTrue(bindings.container(core, activity))
            bindings.ready(owner, bindings.begin(owner))
            assertSame(owner, bindings.select(activity)!!.first)
        }
    }

    @Test fun staleResumeAndReleaseCannotActivateOrInvalidateANewerGeneration() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val activity = Any()
        bindings.wrapper(owner, wrapper, Any()); bindings.activity(owner, activity)
        val old = bindings.begin(owner); val current = bindings.begin(owner)
        bindings.ready(owner, old); assertNull(bindings.select(activity))
        bindings.ready(owner, current); bindings.release(owner, old)
        assertNotNull(bindings.select(activity))
        bindings.release(owner, current); assertNull(bindings.select(activity))
    }

    @Test fun replacingAContainerRequiresANewExplicitAssociationAndInvalidatesTheOldSession() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val old = Any(); val next = Any(); val activity = Any()
        bindings.wrapper(owner, old, Any()); bindings.activity(owner, activity)
        val epoch = bindings.begin(owner); bindings.ready(owner, epoch)
        val candidate = bindings.select(activity)!!.second
        assertTrue(bindings.current(owner, candidate, epoch, old, activity))
        bindings.wrapper(owner, next, Any())
        assertNull(bindings.select(activity)); assertFalse(bindings.current(owner, candidate, epoch, old, activity))
        bindings.container(next, activity)
        assertSame(next, bindings.select(activity)!!.second.wrapper.get())
        bindings.release(owner, epoch); assertNotNull(bindings.select(activity))
    }

    @Test fun aSharedRawCoreDoesNotBindTwoOwnersToAnUnrelatedActivity() {
        val bindings = SponsorPlayerBindings(); val first = Any(); val second = Any(); val core = Any()
        val firstWrapper = Any(); val secondWrapper = Any(); val activity = Any()
        bindings.wrapper(first, firstWrapper, core); bindings.wrapper(second, secondWrapper, core)
        bindings.ready(first, bindings.begin(first)); bindings.ready(second, bindings.begin(second))
        assertFalse(bindings.container(core, activity)); assertNull(bindings.select(activity))
        assertTrue(bindings.container(secondWrapper, activity)); assertSame(second, bindings.select(activity)!!.first)
    }

    @Test fun destroyingTheActivityRemovesBothLiveAndPendingAssociations() {
        val bindings = SponsorPlayerBindings(); val owner = Any(); val wrapper = Any(); val core = Any(); val activity = Any()
        bindings.container(core, activity); bindings.destroy(activity)
        bindings.wrapper(owner, wrapper, core); bindings.ready(owner, bindings.begin(owner))
        assertNull(bindings.select(activity))
        bindings.activity(owner, activity); assertNotNull(bindings.select(activity))
        bindings.destroy(activity); assertNull(bindings.select(activity))
    }
}
