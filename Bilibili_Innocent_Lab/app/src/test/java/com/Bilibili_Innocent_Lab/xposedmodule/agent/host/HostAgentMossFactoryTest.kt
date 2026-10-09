package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import com.bilibili.lib.moss.api.CallOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostAgentMossFactoryTest {
    class Moss(val address: String, val port: Int, val options: CallOptions)
    class NoTimeoutMoss

    @Before fun reset() { CallOptions.ignoreTimeout = false }

    @Test fun `native timeout preserves short deadline and caps expensive calls`() {
        val factory = checkNotNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, Moss::class.java))
        val short = factory.create(125) as Moss
        assertEquals("grpc.biliapi.net", short.address)
        assertEquals(443, short.port)
        assertEquals(125L, short.options.getTimeoutInMs())
        assertEquals(12_000L, (factory.create(120_000) as Moss).options.getTimeoutInMs())
        assertEquals(1L, (factory.create(0) as Moss).options.getTimeoutInMs())
    }

    @Test fun `missing timeout constructor does not fall back to unbounded RPC`() {
        assertNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, NoTimeoutMoss::class.java))
    }

    @Test fun `timeout silently ignored by a drifted host is rejected before creating moss`() {
        val factory = checkNotNull(HostAgentMossFactory.resolve(javaClass.classLoader!!, Moss::class.java))
        CallOptions.ignoreTimeout = true
        val failure = runCatching { factory.create(1000) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("rpc_timeout_unavailable", failure?.message)
    }
}
