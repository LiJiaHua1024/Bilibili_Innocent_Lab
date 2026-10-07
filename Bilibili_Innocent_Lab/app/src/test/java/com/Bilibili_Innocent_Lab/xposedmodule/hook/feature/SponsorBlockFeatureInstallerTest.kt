package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class SponsorBlockFeatureInstallerTest {
    private fun environment(process: String = "tv.danmaku.bili", loader: ClassLoader? = null) = HookEnvironment(
        process, loader, HookPointRegistry(loader), TestHookRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })

    @Test fun disabledAndSecondaryProcessesNeverReachHostLookupOrCreateNetworkWorkers() {
        val unusable = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> = throw AssertionError("Unexpected lookup: $name")
        }
        assertEquals(FeatureInstallResult.Skipped("disabled"), SponsorBlockFeatureInstaller(false, true)
            .install(environment(loader = unusable)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), SponsorBlockFeatureInstaller(true, true)
            .install(environment("tv.danmaku.bili:push", unusable)))
    }

    @Test fun missingLoaderAndMissingDetailBoundaryKeepTheHostUnchanged() {
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), SponsorBlockFeatureInstaller(true, false)
            .install(environment()))
        val withoutDetail = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == SponsorBlockFeatureInstaller.DETAIL) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertEquals(FeatureInstallResult.Skipped("missing-detail-activity"), SponsorBlockFeatureInstaller(true, false)
            .install(environment(loader = withoutDetail)))
    }
}
