package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class AdaptationCacheMaintenanceContractTest {
    @Test fun `cache hit schedules maintenance without synchronous file operations`() {
        val source = SourceContract.read("hook/VersionAdapter.kt")
        val hit = source.after("internal fun ensureAdapted(").after("if (cached != null) {")
            .before("if (!adaptationRunning.compareAndSet(false, true))")
        assertTrue(hit.contains("auditDexSourceAsync(context, cached)"))
        for (operation in listOf("cacheFile()", "writeCache(", "exists()", "inspectHostDexSource(", "codeCacheDir"))
            assertFalse(operation, hit.contains(operation))
        val worker = source.after("private fun auditDexSourceAsync(").before("fun ensureAdapted(")
        assertTrue(worker.contains("HostThreadGuard.run(\"adapter.dex_audit\")"))
        assertTrue(worker.contains("if (!cacheFile().exists()) writeCache(cached)"))
        assertTrue(worker.contains("if (expected == DEX_SOURCE_UNAVAILABLE) return@run"))
        assertTrue(worker.contains("dexAuditRunning.compareAndSet(false, true)"))
    }
}
