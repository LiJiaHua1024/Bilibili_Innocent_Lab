package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 验证真实 HTTP 调度的容量、排队期限和取消；连接替身不接触网络。 */
class AgentHttpSchedulingTest {
    private val source = AgentModelSource(1, "https://example.test/v1", "private-key", "model")

    private class Connection : HttpURLConnection(URL("https://example.test/v1/chat/completions")) {
        val disconnected = AtomicBoolean()
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.set(true) }
        override fun getResponseCode(): Int = 200
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
        override fun getContentLengthLong(): Long = -1
    }

    private fun workers(): ThreadPoolExecutor = AgentHttpsTransport::class.java.getDeclaredField("workers").let { field ->
        field.isAccessible = true
        field.get(AgentHttpsTransport) as ThreadPoolExecutor
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out: $description")
            Thread.sleep(5)
        }
    }

    @Test fun `a hundred consecutive short requests cannot be rejected while previous worker returns to idle`() {
        val connected = AtomicInteger()
        repeat(100) {
            val connection = Connection()
            assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { false }) {
                connected.incrementAndGet()
                connection
            })
            assertTrue(connection.disconnected.get())
        }
        assertEquals(100, connected.get())
    }

    @Test fun `two active and two queued stay bounded while cancelled and expired queued requests never connect`() {
        val pool = workers()
        waitUntil("previous work drained") { pool.activeCount == 0 && pool.queue.isEmpty() }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val connected = AtomicInteger()
        val queuedCancel = AtomicBoolean()
        val activeErrors = arrayOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val queuedErrors = arrayOf(AtomicReference<Throwable?>(), AtomicReference<Throwable?>())
        val activeDone = CountDownLatch(2)
        val queuedDone = CountDownLatch(2)
        val threads = mutableListOf<Thread>()
        fun caller(name: String, done: CountDownLatch, errors: AtomicReference<Throwable?>,
                   cancelled: () -> Boolean, connect: (URL) -> HttpURLConnection): Thread =
            Thread({
                try { assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 10_000, cancelled, connect)) }
                catch (error: Throwable) { errors.set(error) }
                finally { done.countDown() }
            }, name).apply { isDaemon = true; threads += this; start() }

        try {
            repeat(2) { index ->
                caller("http-active-$index", activeDone, activeErrors[index], { false }) {
                    connected.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    Connection()
                }
            }
            assertTrue("Two independent requests must run concurrently", entered.await(2, TimeUnit.SECONDS))
            repeat(2) { index ->
                caller("http-queued-$index", queuedDone, queuedErrors[index], queuedCancel::get) {
                    connected.incrementAndGet()
                    Connection()
                }
            }
            waitUntil("two queued requests") { pool.queue.size == 2 }
            assertEquals(2, pool.activeCount)
            assertEquals(2, pool.maximumPoolSize)
            assertEquals(0, pool.queue.remainingCapacity())
            assertEquals(2, connected.get())

            // 第五份工作只能有界拒绝，不得新增线程或在调用线程执行连接。
            try {
                AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) {
                    connected.incrementAndGet()
                    fail("Saturated transport cannot connect")
                    Connection()
                }
                fail("Bounded backpressure expected")
            } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.NETWORK, error.reason) }
            assertEquals(2, connected.get())

            queuedCancel.set(true)
            assertTrue("Queued cancellation must not wait for active sockets", queuedDone.await(2, TimeUnit.SECONDS))
            queuedErrors.forEach { failure ->
                assertTrue(failure.get() is AgentModelException)
                assertEquals(AgentModelException.Reason.CANCELLED, (failure.get() as AgentModelException).reason)
            }
            waitUntil("cancelled queue slots removed") { pool.queue.isEmpty() }

            // 排队时间属于原期限。两条连接仍堵塞时，第三条超时必须在连接工厂之前停止。
            val started = System.nanoTime()
            try {
                AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 120, { false }) {
                    connected.incrementAndGet()
                    fail("Expired queued work cannot connect")
                    Connection()
                }
                fail("Original deadline expected")
            } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.TIMEOUT, error.reason) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            waitUntil("expired queue slot removed") { pool.queue.isEmpty() }
            assertEquals(2, connected.get())

            release.countDown()
            assertTrue(activeDone.await(2, TimeUnit.SECONDS))
            activeErrors.forEach { assertNull(it.get()) }
            waitUntil("active requests drained") { pool.activeCount == 0 && pool.queue.isEmpty() }
            assertEquals("{}", AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { false }) { Connection() })
            assertEquals(2, connected.get())
        } finally {
            queuedCancel.set(true)
            release.countDown()
            threads.forEach { it.join(2000) }
        }
    }
}
