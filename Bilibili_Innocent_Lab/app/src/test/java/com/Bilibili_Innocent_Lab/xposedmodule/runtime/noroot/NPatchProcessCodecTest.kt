package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchProcessCodecTest {
    private val row = NPatchProcessSnapshot("tv.danmaku.bili:download", 123,
        NPatchMode.EMBEDDED, NPatchProcessState.INSTALLED, "module_direct", 0, 456L, 21L)

    @Test fun `loaded child with zero hooks remains a distinct observation`() {
        assertEquals(listOf(row), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(row))))
        assertEquals(emptyList<NPatchProcessSnapshot>(), NPatchProcessCodec.decode("[]"))
        val isolated = row.copy(processName = "tv.danmaku.bili:web", state = NPatchProcessState.GATED)
        assertEquals(listOf(isolated), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(isolated))))
    }

    @Test fun `protocol rejects forged shape types and unbounded content`() {
        val raw = NPatchProcessCodec.encode(listOf(row))
        for (invalid in listOf(
            raw.replace("tv.danmaku.bili:download", "other.package:download"),
            raw.replace("module_direct", "invented_source"),
            raw.replace("\"hooks\":0", "\"hooks\":\"0\""),
            raw.replace("\"pid\":123", "\"pid\":4294967419"),
            raw.replace("\"hooks\":0", "\"hooks\":4097"),
            raw.replace("\"hooks\":0", "\"hooks\":1").replace("INSTALLED", "DISABLED"),
            raw.replace("\"moduleVersion\":21", "\"moduleVersion\":0"),
            raw.replace("\"pid\":123", "\"pid\":123,\"extra\":true"),
            " ".repeat(NPatchProcessCodec.MAX_CHARS + 1),
            "[$raw,$raw]"
        )) assertNull(invalid.take(120), NPatchProcessCodec.decode(invalid))
        val objectText = raw.removePrefix("[").removeSuffix("]")
        assertNull(NPatchProcessCodec.decode("[$objectText,$objectText]"))
        val tooMany = (1..17).map { row.copy(processName = "tv.danmaku.bili:p$it") }
        assertThrows(IllegalArgumentException::class.java) { NPatchProcessCodec.encode(tooMany) }
    }
}
