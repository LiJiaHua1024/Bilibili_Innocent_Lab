package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.ModuleDiagnosticInputs
import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticHostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode

/** 安装参数与运行回执分别展示；没有回执时不把默认 source=manager 当成运行事实。 */
internal fun nPatchDiagnosticsDetails(context: Context, input: ModuleDiagnosticInputs): String {
    val environment = input.nPatchEnvironment ?: return ""
    val patch = environment.patch
    if (!patch.markerPresent && !input.noRootDesiredEnabled) return ""
    val unknown = context.getString(R.string.npatch_profile_unknown)
    val mode = context.getString(when (patch.mode) {
        NPatchMode.MANAGER -> R.string.npatch_profile_manager
        NPatchMode.EMBEDDED -> R.string.npatch_profile_embedded
        NPatchMode.UNKNOWN -> R.string.npatch_profile_unknown
    })
    return buildList {
        add(context.getString(R.string.npatch_profile_installed, mode,
            patch.signatureLevel?.toString() ?: unknown,
            patch.loaderVersionCode?.toString() ?: unknown,
            patch.loaderApi?.toString() ?: unknown))
        add(context.getString(R.string.npatch_profile_flags,
            patch.debuggable?.toString() ?: unknown, unknown))
        add(context.getString(R.string.npatch_profile_manager_version,
            environment.managerVersionName ?: unknown,
            environment.managerVersionCode?.toString() ?: unknown))
        if (environment.versionMismatch) add(context.getString(R.string.npatch_profile_version_mismatch))
        if (patch.mode == NPatchMode.EMBEDDED) add(context.getString(R.string.npatch_profile_embed_experimental))
        if (input.hostRuntimeReceiptAvailable && input.hostConfigState == DiagnosticHostConfigState.ACCEPTED) {
            add(context.getString(R.string.npatch_profile_source, input.hostConfigSource))
        } else add(context.getString(R.string.npatch_profile_source_unknown))
        if (input.noRootSyncDetail?.startsWith("manager_cache_") == true) {
            add(context.getString(R.string.npatch_profile_cache_unavailable))
        }
    }.joinToString("\n")
}
