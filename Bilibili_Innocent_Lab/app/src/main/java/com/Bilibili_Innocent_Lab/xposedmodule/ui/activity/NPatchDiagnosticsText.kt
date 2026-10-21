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
        add(context.getString(R.string.npatch_process_observation))
        if (input.nPatchProcesses.isEmpty()) add(context.getString(R.string.npatch_process_unknown))
        input.nPatchProcesses.forEach { row ->
            val state = context.getString(when (row.state) {
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.LOADED -> R.string.npatch_process_loaded
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.REJECTED -> R.string.npatch_process_rejected
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.DISABLED -> R.string.npatch_process_disabled
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.GATED -> R.string.npatch_process_gated
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.INSTALLED -> R.string.npatch_process_installed
                com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchProcessState.FAILED -> R.string.npatch_process_failed
            })
            add(context.getString(R.string.npatch_process_row, row.processName, row.pid,
                state, row.installedHookCount, row.source))
        }
    }.joinToString("\n")
}
