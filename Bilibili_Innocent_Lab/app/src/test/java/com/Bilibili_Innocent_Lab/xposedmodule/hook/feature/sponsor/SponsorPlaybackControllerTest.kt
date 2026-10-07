package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Test

class SponsorPlaybackControllerTest {
    private class Harness(auto: Boolean = false) : SponsorPlayerPort {
        val id = SponsorVideoId("BV14741127BN", 1)
        var identity = id; var foreground = true; var playing = true; var position = 12_000L
        var progressAvailable = true
        var time = 0L; var applied = 0; var cancelled = 0
        var hint = SponsorPlaybackController.Hint.NONE
        lateinit var result: (SponsorFetchResult) -> Unit
        data class Seek(val target: Int, val valid: () -> Boolean, val executing: () -> Unit)
        val seeks = mutableListOf<Seek>()
        val queue = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = SponsorPlaybackController(id, auto, this,
            { _, callback -> result = callback; SponsorSegmentRepository.Ticket { cancelled++ } },
            { queue.add(it) }, { delay, callback ->
                var active = true
                timers += delay to { if (active) callback() }
                SponsorSegmentRepository.Ticket { active = false }
            }, { time }, { hint = it }, { applied++ })
        override fun valid(video: SponsorVideoId) = foreground && identity == video
        override fun position() = if (progressAvailable) position else -1L
        override fun duration() = if (progressAvailable) 100_000L else 0L
        override fun playing() = playing
        override fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean {
            seeks += Seek(targetMs, valid, executing); return true
        }
        fun deliver() { result(SponsorFetchResult.Available(listOf(SponsorSegment("a", 10_000, 20_000, 100_000)))); drain() }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun execute(): Boolean {
            val seek = seeks.last()
            if (!seek.valid()) return false
            seek.executing(); controller.seekStarted(seek.target.toLong())
            position = seek.target.toLong(); controller.seekCompleted()
            return true
        }
    }

    @Test fun manualSkipShowsUndoOnlyAfterNativeConfirmationAndUndoDoesNotLoop() {
        val h = Harness(); h.controller.start(); h.deliver()
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
        h.controller.skip(); assertEquals(0, h.applied)
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        assertTrue(h.execute()); assertEquals(1, h.applied)
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
        h.controller.seekCompleted()
        assertEquals(SponsorPlaybackController.Hint.UNDO, h.hint)
        assertEquals(1, h.applied)
        h.controller.undo(); assertTrue(h.execute()); assertEquals(12_000L, h.position)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun switchingPartOrLeavingForegroundRejectsAlreadyQueuedSeekAndLateFetch() {
        for (switch in listOf(true, false)) {
            val h = Harness(true); h.controller.start(); h.deliver()
            if (switch) h.identity = h.id.copy(cid = 2) else h.foreground = false
            assertFalse(h.execute())
            h.controller.stop(); h.deliver()
            assertEquals(1, h.cancelled)
            assertEquals(1, h.seeks.size)
            assertEquals(0, h.applied)
            assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        }
    }

    @Test fun manualScrubbingBeforeFetchReturnsCannotTriggerAnAutomaticSkip() {
        val h = Harness(true); h.controller.start()
        h.controller.externalSeekStarted(); h.position = 15_000; h.controller.seekCompleted()
        h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun pauseRejectsAutomaticQueuedWorkButManualSkipWhilePausedIsAllowed() {
        val h = Harness(true); h.controller.start(); h.deliver(); h.playing = false
        h.controller.stateChanged(); assertFalse(h.execute())
        h.controller.skip(); h.controller.stateChanged(); assertTrue(h.execute())
        assertEquals(1, h.applied)
    }

    @Test fun timeoutRejectsExecutionWithoutFalselyReportingApplied() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.time = 4_000; h.timers.single().second()
        assertFalse(h.execute()); assertEquals(0, h.applied)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun userSeekCancelsAnAlreadyQueuedSkip() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        assertFalse(h.execute())
        h.position = 16_000; h.controller.seekCompleted(); h.controller.refresh()
        assertEquals(1, h.seeks.size)
        assertEquals(SponsorPlaybackController.Hint.SKIP, h.hint)
    }

    @Test fun missingSeekCompleteUsesAnIndependentDeadlineToRestoreManualControls() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.externalSeekStarted()
        h.time = 4_000; h.timers.last().second()
        assertEquals(1, h.seeks.size)
        h.playing = false; h.controller.skip()
        assertEquals(2, h.seeks.size)
        assertTrue(h.execute())
    }

    @Test fun userGestureInvalidatesTheGateBeforeTheMainQueueProcessesIt() {
        val h = Harness(true); h.controller.start(); h.deliver()
        h.controller.invalidateExternalSeek()
        assertFalse(h.execute())
        h.controller.externalSeekStarted(); h.position = 16_000; h.controller.seekCompleted()
        assertEquals(0, h.applied)
    }

    @Test fun aDifferentCompletionPositionWithoutAStartEventStillClearsTheOldUndo() {
        val h = Harness(); h.controller.start(); h.deliver(); h.controller.skip(); h.execute()
        h.position = 50_000; h.controller.seekCompleted()
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.controller.undo()
        assertEquals(1, h.seeks.size)
    }

    @Test fun cachedSegmentsCannotUseResidualProgressBeforeTheFirstNativeNotification() {
        val h = Harness(true); h.progressAvailable = false
        h.controller.start(); h.deliver()
        assertTrue(h.seeks.isEmpty())
        assertEquals(SponsorPlaybackController.Hint.NONE, h.hint)
        h.progressAvailable = true; h.controller.refresh()
        assertEquals(1, h.seeks.size)
    }
}
