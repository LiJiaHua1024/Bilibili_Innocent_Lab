package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class HostTopIslandRefreshAccessTest {
    open class NativeRefresh {
        var busy = false
        var notifications = 0
        fun isRefreshing() = busy
        @Suppress("UNUSED_PARAMETER") open fun setOnRefreshListener(listener: Runnable) = Unit
        @Suppress("unused") private fun m(refreshing: Boolean, notify: Boolean) {
            busy = refreshing
            if (notify) notifications++
        }
    }
    class HostSubclass : NativeRefresh() {
        override fun setOnRefreshListener(listener: Runnable) = Unit
    }
    class NamedRefresh {
        var busy = false
        var notified = false
        fun isRefreshing() = busy
        @Suppress("unused") private fun setRefreshing(refreshing: Boolean, notify: Boolean) {
            busy = refreshing
            notified = notify
        }
    }
    class AmbiguousRefresh {
        @Suppress("UNUSED_PARAMETER") fun setOnRefreshListener(listener: Runnable) = Unit
        @Suppress("UNUSED_PARAMETER", "unused") private fun a(x: Boolean, y: Boolean) = Unit
        @Suppress("UNUSED_PARAMETER", "unused") private fun b(x: Boolean, y: Boolean) = Unit
    }

    @Test fun nativeNotificationPathWorksThroughAHostSubclassAndPreventsDuplicates() {
        val target = HostSubclass()
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertTrue(target.busy)
        assertEquals(1, target.notifications)
        assertFalse(HostTopIslandRefreshAccess.refresh(target))
        assertEquals(1, target.notifications)
        target.busy = false
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertEquals(2, target.notifications)
    }

    @Test fun ambiguousObfuscatedMethodsAreNeverInvoked() {
        assertNull(HostTopIslandRefreshAccess.notifyMethod(AmbiguousRefresh::class.java))
    }

    @Test fun standardAndroidxNamedEntryAlsoNotifiesTheNativeListener() {
        val target = NamedRefresh()
        assertTrue(HostTopIslandRefreshAccess.refresh(target))
        assertTrue(target.busy)
        assertTrue(target.notified)
    }
}
