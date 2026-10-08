package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.content.Context
import android.annotation.SuppressLint
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONObject

/** 仅开关进入宿主配置；任务、选源、截图许可和能力探测记录均留在模块私有文件。 */
internal object AgentPreferences {
    const val ENABLED = "agent_enabled"
    const val CATALOG_ID = "agent.enabled"
    const val CAPABILITY_TTL_MS = 24 * 60 * 60_000L
    private const val FILE = "agent_module_private"

    fun sources(context: Context): List<AgentModelSource> {
        val preferences = context.prefs()
        return (1..SemanticSource.MAX_SOURCES).mapNotNull { index ->
            val (provider, endpoint, model) = FeaturePreferences.semanticSourceKeys(index)
            val kind = preferences.getString(provider, SemanticBackend.JEV).orEmpty()
            if (kind == SemanticBackend.JEV) return@mapNotNull null
            val source = SemanticSource.from(index,
                preferences.getString(RemoteHookConfigContract.semanticApiKey(index), "").orEmpty(),
                preferences.getString(endpoint, "").orEmpty(), kind,
                preferences.getString(model, "").orEmpty()) ?: return@mapNotNull null
            AgentModelSource.from(index, source.endpoint, source.apiKey, source.backend.model)
        }
    }

    fun selected(context: Context): Set<Int> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("sources", "").orEmpty()
            .split(',').mapNotNull(String::toIntOrNull).filter { it in 1..SemanticSource.MAX_SOURCES }.toSet()

    /** 授权保存必须判断 commit 的布尔结果，KTX edit 丢弃该结果。 */
    @SuppressLint("UseKtx")
    fun saveSelection(context: Context, indices: Set<Int>, fixed: Int?, vision: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("sources", indices.filter { it in 1..SemanticSource.MAX_SOURCES }.sorted().joinToString(","))
            .putInt("fixed", fixed ?: 0).putBoolean("vision", vision).commit()

    fun fixed(context: Context): Int? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt("fixed", 0).takeIf { it in 1..SemanticSource.MAX_SOURCES }
    fun visionAllowed(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("vision", false)

    /** SharedPreferences.commit 失败也可能已更新内存；必须回写原值，不能只恢复开关外观。 */
    fun writeEnabled(previous: Boolean, desired: Boolean, commit: (Boolean) -> Boolean): Boolean {
        if (runCatching { commit(desired) }.getOrDefault(false)) return true
        runCatching { commit(previous) }
        return false
    }

    fun capabilities(context: Context, source: AgentModelSource): AgentModelCapabilities? = runCatching {
        val text = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("cap_${source.fingerprint}", null)
            ?: return null
        AgentModelCapabilities.fromJson(JSONObject(text))?.takeIf {
            val age = System.currentTimeMillis() - it.checkedAtMs
            age in 0..CAPABILITY_TTL_MS
        }
    }.getOrNull()

    @SuppressLint("UseKtx") // 只有真实提交成功，UI 才能报告检测证明已保存。
    fun saveCapabilities(context: Context, source: AgentModelSource, result: AgentModelCapabilities): Boolean {
        val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = sources(context).map { "cap_${it.fingerprint}" }.toSet()
        val edit = store.edit()
        store.all.keys.filter { it.startsWith("cap_") && it !in current }.forEach(edit::remove)
        return edit.putString("cap_${source.fingerprint}", result.toJson().toString()).commit()
    }
}
