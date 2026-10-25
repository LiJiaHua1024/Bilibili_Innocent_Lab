package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.Cause
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.State
import org.junit.Assert.*
import org.junit.Test

class FramePresentationRecoveryTest {
    private val second = 1_000_000_000L
    private fun started() = FramePresentationRecovery(window = 20).also { assertTrue(it.start(0L)) }
    private fun FramePresentationRecovery.onTime(count: Int) = repeat(count) { onPresented(0L, 0L, 8_000_000L) }
    private fun FramePresentationRecovery.late() = onPresented(0L, 20_000_000L, 8_000_000L)

    @Test fun `repeated clock rejection falls back even with successful presentations in between`() {
        val recovery = started()
        repeat(3) { i ->
            recovery.onTime(120)
            recovery.onClockRejected(i * second)
        }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertEquals(Cause.UNVERIFIED_CLOCK, recovery.report().cause)
        assertFalse(recovery.report().pipelinePresents)
        assertTrue(recovery.start(3 * second))
        assertTrue("a retry starts a fresh clock budget", recovery.onClockRejected(3 * second).pipelinePresents)
    }

    @Test fun `isolated clock rejections expire instead of disabling a long playing video`() {
        val recovery = started()
        for (time in listOf(0L, 2L, 8L, 10L)) assertTrue(recovery.onClockRejected(time * second).pipelinePresents)
        assertEquals(State.FALLBACK, recovery.onClockRejected(12 * second).state)
        assertEquals(Cause.UNVERIFIED_CLOCK, recovery.report().cause)
    }

    @Test fun `on time presentation keeps interpolating`() {
        val recovery = started()
        recovery.onTime(40)
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertTrue(recovery.report().interpolationAllowed)
    }

    @Test fun `scattered late frames degrade to originals then recover after a clean window`() {
        val recovery = started()
        recovery.onTime(9)
        recovery.late()
        assertEquals(State.DEGRADED, recovery.report().state)
        assertEquals(Cause.LATE_FRAMES, recovery.report().cause)
        assertFalse(recovery.report().interpolationAllowed)
        assertTrue(recovery.report().pipelinePresents)
        recovery.onTime(19)
        assertEquals(State.DEGRADED, recovery.report().state)
        recovery.onTime(1)
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertEquals(Cause.RECOVERED, recovery.report().cause)
    }

    @Test fun `consecutive drops fall back to native and retry only after backoff on a new epoch`() {
        val recovery = started()
        repeat(3) { recovery.onDropped(0L) }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertFalse(recovery.report().pipelinePresents)
        assertFalse(recovery.start(second - 1))
        assertTrue(recovery.start(second))
        assertEquals(Cause.RETRY, recovery.report().cause)
        assertEquals(1, recovery.report().retries)
        assertEquals(0, recovery.report().failures)
    }

    @Test fun `backoff doubles and retries are bounded per session`() {
        val recovery = FramePresentationRecovery(window = 20, maxRetries = 2).also { it.start(0L) }
        recovery.onSurfaceLost(0L)
        assertTrue(recovery.start(second))
        recovery.onSurfaceLost(second)
        assertFalse(recovery.start(second + 2 * second - 1))
        assertTrue(recovery.start(second + 2 * second))
        recovery.onSurfaceLost(10 * second)
        assertFalse(recovery.start(100 * second))
        assertEquals(State.DISABLED, recovery.report().state)
        assertEquals(Cause.RETRIES_EXHAUSTED, recovery.report().cause)
    }

    @Test fun `fatal native status disables for the session and survives stop`() {
        val recovery = started()
        recovery.onNativeStatus(Status.GPU_UNAVAILABLE, 0L)
        assertEquals(State.DISABLED, recovery.report().state)
        assertEquals(Status.GPU_UNAVAILABLE, recovery.report().nativeStatus)
        recovery.stop()
        assertFalse(recovery.start(100 * second))
    }

    @Test fun `transient native errors count as failures and report the status`() {
        val recovery = started()
        recovery.onTime(9)
        assertEquals(State.DEGRADED, recovery.onNativeStatus(Status.INFERENCE_FAILED, 0L).state)
        assertEquals(Status.INFERENCE_FAILED, recovery.report().nativeStatus)
        recovery.onNativeStatus(Status.BUSY, 0L)
        recovery.onNativeStatus(Status.INVALID_OUTPUT, 0L)
        assertEquals(State.FALLBACK, recovery.report().state)
    }

    @Test fun `warm up samples after start are ignored and transitions keep the real failure cause`() {
        val recovery = FramePresentationRecovery(window = 20, warmupSamples = 3).also { it.start(0L) }
        repeat(3) { recovery.onDropped(0L) }
        assertEquals(State.INTERPOLATING, recovery.report().state)
        assertEquals(0, recovery.report().samples)
        recovery.onTime(6)
        recovery.onDropped(0L)
        recovery.onTime(1)
        recovery.onDropped(0L)
        // 第 9 个样本是成功样本，但 2/9 ≥ 10% 触发降级：原因应为最近一次失败，而不是 NONE。
        recovery.onTime(1)
        assertEquals(State.DEGRADED, recovery.report().state)
        assertEquals(Cause.DROPPED_FRAMES, recovery.report().cause)
    }

    @Test fun `listeners observe every transition and outcomes outside the pipeline are ignored`() {
        val recovery = FramePresentationRecovery(window = 20)
        val seen = ArrayList<State>()
        recovery.addListener { seen += it.state }
        recovery.onDropped(0L)
        assertEquals(State.INACTIVE, recovery.report().state)
        recovery.start(0L)
        recovery.onSurfaceLost(0L)
        repeat(5) { recovery.onDropped(0L) }
        recovery.stop()
        assertEquals(listOf(State.INTERPOLATING, State.FALLBACK), seen)
    }
}
