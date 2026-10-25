package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameTakeoverPolicyTest {
    @Test fun surfaceHolderAndSurfacePathsRetainOnlyTheCurrentMainPlayerSurface() {
        val ownership = FrameSurfaceOwnership<Any, Any>()
        val player = Any()
        val holderSurface = Any()
        val replacement = Any()
        assertFalse(ownership.bind(Any(), holderSurface, false))
        assertTrue(ownership.bind(player, holderSurface, true))
        assertTrue(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, null, false))
        assertFalse(ownership.contains(holderSurface))
        assertTrue(ownership.bind(player, replacement, false))
        assertTrue(ownership.contains(replacement))
        ownership.release(player)
        assertFalse(ownership.contains(replacement))
        assertFalse(ownership.bind(player, replacement, false))
    }

    private class EqualObject {
        override fun equals(other: Any?) = other is EqualObject
        override fun hashCode() = 1
    }

    @Test fun hostEqualityNeverGrantsAnUnrelatedPlayerOrSurfaceOwnership() {
        val ownership = FrameSurfaceOwnership<EqualObject, EqualObject>()
        val surface = EqualObject()
        assertTrue(ownership.bind(EqualObject(), surface, true))
        assertFalse(ownership.contains(EqualObject()))
        assertFalse(ownership.bind(EqualObject(), surface, false))
    }

    @Test fun onePlayerReleasingASharedSurfaceDoesNotRevokeAnotherOwner() {
        val ownership = FrameSurfaceOwnership<Any, Any>(2)
        val first = Any(); val second = Any(); val shared = Any()
        assertTrue(ownership.bind(first, shared, true))
        assertTrue(ownership.bind(second, shared, true))
        assertFalse(ownership.bind(Any(), Any(), true))
        ownership.release(first)
        assertTrue(ownership.contains(shared))
        ownership.release(second)
        assertFalse(ownership.contains(shared))
    }

    @Test fun nestedAdapterCallsAndExceptionalExitPreserveAndThenClearTheirScope() {
        val scope = FrameCallScope()
        scope.enter()
        try {
            scope.enter()
            try { assertTrue(scope.active); throw IllegalStateException() }
            catch (_: IllegalStateException) { }
            finally { scope.leave() }
            assertTrue(scope.active)
        } finally { scope.leave() }
        assertFalse(scope.active)
    }

    @Test fun stoppedAndReconfiguredDecodersRejectLateBridgesWithoutCancellingTheirReplacement() {
        val generation = FrameDecoderGeneration<Any>()
        val codec = Any()
        val old = generation.begin(codec)
        generation.end(codec)
        assertFalse(generation.commit(codec, old) { fail("released decoder") })
        val next = generation.begin(codec)
        assertFalse(generation.end(codec, old) { fail("old failure must not remove a new bridge") })
        assertTrue(generation.commit(codec, next) {})
        val newer = generation.begin(codec)
        assertFalse(generation.commit(codec, next) { fail("reconfigured decoder") })
        assertTrue(generation.matches(codec, newer))
    }

    @Test fun onlyTheTwoKnownConfigureAbisAreAcceptedAndEncryptedArgumentsKeepTheirOwnIndex() {
        val prefix = listOf("android.media.MediaFormat", "android.view.Surface")
        assertEquals(2, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int")))
        assertEquals(3, FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "android.media.MediaDescrambler")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("int", "java.lang.Object")))
        assertNull(FrameCodecConfigureShape.cryptoIndex(prefix + listOf("android.media.MediaCrypto", "int", "int")))
    }

    @Test fun optionalHooksCannotMaskAMissingConfigureOrSurfaceRole() {
        val required = setOf("display", "op", "surface", "configure", "flush", "stop", "release", "audio.ctor")
        assertTrue(FrameHookCoverage.complete(required))
        assertFalse(FrameHookCoverage.complete(required - "configure" + setOf("display.resume", "display.pause", "surface.holder")))
        assertFalse(FrameHookCoverage.complete(required - "surface" + setOf("display.resume", "display.pause")))
    }
}
