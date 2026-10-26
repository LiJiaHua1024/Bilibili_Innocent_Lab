package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FrameworkServiceCallTest {
    private val ready = ModernFrameworkStatus(true, true, "LSPosed", 102, connectionId = 7)

    @Test fun missingServiceAndMissingCapabilityDoNotExecuteTheOperation() {
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.NOT_CONNECTED),
            callFrameworkService<Any, Unit>(null, ready, { true }, {}) { error("must not execute") })
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.UNSUPPORTED),
            callFrameworkService(Any(), ready.copy(capable = false, properties = 0L), { true }, {}) { error("must not execute") })
    }

    @Test fun unknownRoutingIdentityIsPendingRatherThanUnsupported() {
        val pending = readModernFrameworkCapabilities({ 102 }, { io.github.libxposed.service.XposedService.PROP_CAP_REMOTE })
            .withModernFrameworkIdentity { error("not read yet") }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.METADATA_UNAVAILABLE),
            callFrameworkService(Any(), pending, { true }, {}) { error("must not execute") })
    }

    @Test fun aReplacedConnectionCannotReportTheOldModelAsReady() {
        var current = true
        val result = callFrameworkService(Any(), ready, { current }, {}) { current = false; "ready" }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.CONNECTION_CHANGED), result)
    }

    @Test fun fileErrorsDoNotInvalidateALiveService() {
        var died = false
        val result = callFrameworkService(Any(), ready, { true }, { died = true }) { throw IOException("disk") }
        assertEquals(FrameworkServiceCall.Failure(FrameworkServiceCall.Reason.OPERATION_FAILED), result)
        assertFalse(died)
        assertEquals(FrameworkServiceCall.Success("ready", 7),
            callFrameworkService(Any(), ready, { true }, {}) { "ready" })
    }

    @Test fun serviceDeathClassificationUnwrapsSdkExceptionsButIsBounded() {
        val dead = IOException("dead")
        assertTrue(isFrameworkServiceDeath(RuntimeException(RuntimeException(dead))) { it === dead })
        assertFalse(isFrameworkServiceDeath(IOException("storage")) { it === dead })
        var chain: Throwable = dead
        repeat(10) { chain = RuntimeException(chain) }
        assertFalse(isFrameworkServiceDeath(chain) { it === dead })
    }

    @Test fun capabilitiesCanBePublishedBeforeOptionalMetadataIsRead() {
        val core = readModernFrameworkCapabilities({ 102 }, { io.github.libxposed.service.XposedService.PROP_CAP_REMOTE })
        assertTrue(core.capable)
        assertEquals("", core.name)
        val described = core.withModernFrameworkMetadata({ "LSPosed" }, { error("unavailable") }, { 7930 })
        assertTrue(described.capable)
        assertEquals("LSPosed", described.name)
    }
}
