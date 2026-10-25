package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class FrameCaptureTimingContractTest {
    @Test fun `revoked bridges cannot restore codec output or reset a replacement audio clock`() {
        val source = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
        val restore = source.after("private fun onNativeRestored(").before("companion object")
        val guard = restore.indexOf("if (closed.get() || !isCurrent())")
        val switch = restore.indexOf("decoder.setOutputSurface(display)")
        assertTrue(guard >= 0 && switch > guard)
        val close = source.after("fun close()").before("private fun onFrame(")
        assertTrue(close.contains("if (isCurrent()) host.setAudioDelayUs(0L)"))
        val installer = SourceContract.read("hook/feature/interpolation/FrameInterpolationFeatureInstaller.kt")
        assertTrue(installer.contains("isCurrent = decoder.ticket::isActive"))
        assertTrue(installer.contains("pending.await(codec, decoder, { engine?.ready() == true }"))
    }
    @Test fun `clock rejection enters recovery before its observation window is reset`() {
        val rejection = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
            .after("if (state == FrameTimeline.ClockState.RESTART_REQUIRED").before("clock.reset()")
        assertTrue("repeated invalid snapshots cannot bypass fallback by starting another epoch",
            rejection.contains("if (!session.onClockRejected(now)) return"))
    }

    /**
     * 通知时刻只能经 [FrameArrivalStamp] 使用：它在 PTS 节奏不一致（BufferQueue 顶替、首帧、回跳）时改用锁存之后的时刻，
     * 因此通知时刻不会描述锁存期间换上的更新的帧；锁存时刻必须在可能阻塞的 updateTexImage 之后测得。
     */
    @Test fun `host clock observation never pairs a notification time with a newer latched frame`() {
        val callback = SourceContract.read("hook/feature/interpolation/FrameGlCapture.kt")
            .after("private fun onFrameAvailable(").before("private fun read(")
        val latch = callback.indexOf("runCatching { texture.updateTexImage() }")
        val observation = callback.indexOf("val latched = System.nanoTime()")
        val delivery = callback.indexOf("listener.onFrame(timestamp, stamp.arrival(timestamp, notifiedNs, latched))")
        assertTrue("latch time is measured after the potentially blocking latch", latch >= 0 && observation > latch)
        assertTrue("notification time is only used through FrameArrivalStamp", delivery > observation)
        assertFalse("raw notification time must not reach the clock", callback.contains("listener.onFrame(timestamp, notifiedNs"))
    }
}
