package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ReplyTopologyClipboardWriteTest {
    @Test fun onlyTheExactClipIsAllowedDuringItsSynchronousWrite() {
        val clip = Any()
        val other = Any()
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
        val result = ReplyTopologyClipboardWrite.write(clip) {
            assertTrue(ReplyTopologyClipboardWrite.owns(clip))
            assertFalse(ReplyTopologyClipboardWrite.owns(other))
            "written"
        }
        assertEquals("written", result)
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
    }

    @Test fun failedWritesStillRemoveTheExemption() {
        val clip = Any()
        assertTrue(runCatching {
            ReplyTopologyClipboardWrite.write(clip) { error("clipboard failed") }
        }.isFailure)
        assertFalse(ReplyTopologyClipboardWrite.owns(clip))
    }

    @Test fun nestedWritesRestoreThePreviousClipIdentity() {
        val outer = Any()
        val inner = Any()
        ReplyTopologyClipboardWrite.write(outer) {
            ReplyTopologyClipboardWrite.write(inner) {
                assertTrue(ReplyTopologyClipboardWrite.owns(inner))
                assertFalse(ReplyTopologyClipboardWrite.owns(outer))
            }
            assertTrue(ReplyTopologyClipboardWrite.owns(outer))
        }
        assertFalse(ReplyTopologyClipboardWrite.owns(outer))
        assertFalse(ReplyTopologyClipboardWrite.owns(inner))
    }

    @Test fun anotherThreadCannotBorrowTheExemption() {
        val clip = Any()
        val allowed = AtomicBoolean(true)
        ReplyTopologyClipboardWrite.write(clip) {
            val thread = Thread { allowed.set(ReplyTopologyClipboardWrite.owns(clip)) }
            thread.start()
            thread.join(2_000L)
            assertFalse(thread.isAlive)
        }
        assertFalse(allowed.get())
    }
}
