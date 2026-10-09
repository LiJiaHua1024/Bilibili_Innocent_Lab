package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockClientTest {
    private val id = SponsorVideoId("BV14741127BN", 1)
    private class Connection(private val status: Int, private val body: InputStream) : HttpURLConnection(URL("https://www.bsbsb.top")) {
        var released = false
        override fun getResponseCode() = status
        override fun getInputStream() = body
        override fun disconnect() { released = true }
        override fun usingProxy() = false
        override fun connect() {}
    }
    private fun fetch(status: Int, body: String = "[]"): SponsorFetchResult {
        val connection = Connection(status, ByteArrayInputStream(body.toByteArray()))
        val result = SponsorBlockClient("test") { connection }.fetch(id, SponsorRequestCancellation())
        assertTrue(connection.released); assertFalse(connection.instanceFollowRedirects)
        return result
    }

    @Test fun onlyTransientHttpFailuresHaveRetryBackoff() {
        assertEquals(SponsorFetchResult.Unavailable("rate-limited", 60_000), fetch(429))
        assertEquals(SponsorFetchResult.Unavailable("http-503", 5_000), fetch(503))
        assertEquals(SponsorFetchResult.Unavailable("http-403"), fetch(403))
        assertEquals(SponsorFetchResult.Unavailable("http-302"), fetch(302))
        assertEquals(SponsorFetchResult.Available(emptyList()), fetch(404))
    }

    @Test fun networkTlsAndInvalidResponsesHaveDistinctReasons() {
        for ((error, result) in listOf(SocketTimeoutException() to SponsorFetchResult.Unavailable("timeout", 2_000),
            UnknownHostException() to SponsorFetchResult.Unavailable("dns-error", 2_000),
            SSLException("private text") to SponsorFetchResult.Unavailable("tls-error"))) {
            assertEquals(result, SponsorBlockClient("test") { throw error }.fetch(id, SponsorRequestCancellation()))
        }
        assertEquals(SponsorFetchResult.Unavailable("invalid-response"), fetch(200, "not-json"))
        assertEquals(SponsorFetchResult.Unavailable("invalid-response"),
            fetch(200, "x".repeat(SponsorBlockClient.MAX_RESPONSE_BYTES + 1)))
    }

    @Test fun cancellationBeforeOpeningDoesNotStartANetworkRequestOrRetry() {
        var opened = false
        val cancellation = SponsorRequestCancellation().apply { cancel() }
        val result = SponsorBlockClient("test") { opened = true; error("must not open") }.fetch(id, cancellation)
        assertFalse(opened); assertEquals(SponsorFetchResult.Unavailable("cancelled"), result)
    }
}
