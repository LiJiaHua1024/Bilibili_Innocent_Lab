package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class FramePlayerClockProbeTest {
    class Player {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()
        val fail = AtomicBoolean(false)
        fun getCurrentPosition(): Long {
            calls.incrementAndGet(); entered.countDown()
            check(gate.await(2, TimeUnit.SECONDS))
            check(!fail.get())
            return 100L
        }
        fun isPlaying() = true
        fun getPropertyFloat(key: Int, fallback: Float) = if (key == 10003) 1f else fallback
    }

    @Test fun `release does not wait on the player query and stops subsequent polling`() {
        val player = Player()
        val probe = FramePlayerClockProbe({ _, _ -> }, { true })
        try {
            probe.attach(player)
            probe.attach(player)
            assertTrue(player.entered.await(1, TimeUnit.SECONDS))
            probe.remove(player) // 当前查询仍在等 gate；不得在宿主 release 上等它。
            player.gate.countDown()
            Thread.sleep(80)
            assertEquals(1, player.calls.get())
        } finally { player.gate.countDown(); probe.remove(player) }
    }

    @Test fun `disabled marker stops the source before querying it`() {
        val detached = CountDownLatch(1)
        val player = Player().apply { gate.countDown() }
        val probe = FramePlayerClockProbe({ event, _ -> if (event == "clock.detached") detached.countDown() }, { false })
        try {
            probe.attach(player)
            assertTrue(detached.await(1, TimeUnit.SECONDS))
            assertEquals(0, player.calls.get())
        } finally { probe.remove(player) }
    }

    @Test fun `a failing query is reported without killing the sampler`() {
        val failure = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val player = Player().apply { gate.countDown(); fail.set(true) }
        val probe = FramePlayerClockProbe({ event, detail ->
            if (event == "clock.player" && detail.contains("failures=1")) failure.countDown()
            if (event == "clock.jump") recovered.countDown()
        }, { true })
        try {
            probe.attach(player)
            assertTrue(failure.await(1, TimeUnit.SECONDS))
            player.fail.set(false)
            // 固定媒体位置配连续墙钟在下一对成功样本上产生跳变，证明异常之后仍在采样。
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
        } finally { probe.remove(player) }
    }
}
