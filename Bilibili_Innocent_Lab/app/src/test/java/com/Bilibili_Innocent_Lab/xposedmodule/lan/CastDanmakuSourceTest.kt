package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class CastDanmakuSourceTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?><i><chatid>41074626031</chatid><d p="1.5,1,25,16711680,0">中文弹幕 &amp; timing</d></i>""".toByteArray()
    private fun deflate(bytes: ByteArray, raw: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val compressor = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
        try { DeflaterOutputStream(output, compressor).use { it.write(bytes) } }
        finally { compressor.end() }
        return output.toByteArray()
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
    private class Reply(private val bytes: ByteArray, private val encoding: String?, private val status: Int = 200) :
        HttpURLConnection(URL("https://comment.bilibili.com/41074626031.xml")) {
        var closed = false
        var read = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentEncoding() = encoding
        override fun getInputStream() = bytes.inputStream().also { read = true }
    }
    private fun verify(bytes: ByteArray, encoding: String?) {
        val reply = Reply(bytes, encoding)
        val comments = CastDanmakuSource.fetch(41074626031) { url ->
            assertEquals("https://comment.bilibili.com/41074626031.xml", url)
            reply
        }
        assertEquals(1, comments.size)
        assertEquals("中文弹幕 & timing", comments.single().text)
        assertEquals(1500L, comments.single().atMs)
        assertTrue(reply.closed)
    }
    @Test fun `raw deflate returned by the real comment endpoint is decoded before XML parsing`() = verify(deflate(xml, true), "deflate")
    @Test fun `zlib wrapped deflate is also supported`() = verify(deflate(xml, false), "DeFlAtE")
    @Test fun `gzip comments retain text and timing`() = verify(gzip(xml), "gzip")
    @Test fun `plain comments require no compression header`() = verify(xml, null)
    @Test fun `inflated body budget and unsupported encodings are rejected`() {
        val compressed = deflate(ByteArray(8 * 1024 * 1024 + 1) { 65 }, true)
        val failure = runCatching { CastDanmakuSource.readBody(compressed.inputStream(), "deflate") }.exceptionOrNull()
        assertEquals("response-too-large", failure?.message)
        assertTrue(runCatching { CastDanmakuSource.readBody(xml.inputStream(), "br") }.isFailure)
    }
    @Test fun `truncated compressed body is not treated as valid XML`() {
        val reply = Reply(gzip(xml).dropLast(8).toByteArray(), "gzip")
        assertTrue(runCatching { CastDanmakuSource.fetch(41074626031) { reply } }.isFailure)
        assertTrue(reply.closed)
    }
    @Test fun `HTTP error is not parsed and connection is closed`() {
        val reply = Reply("<html>error</html>".toByteArray(), null, 403)
        assertEquals("danmaku-unavailable", runCatching { CastDanmakuSource.fetch(41074626031) { reply } }.exceptionOrNull()?.message)
        assertFalse(reply.read)
        assertTrue(reply.closed)
    }
}
