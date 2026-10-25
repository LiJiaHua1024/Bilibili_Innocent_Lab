package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject

/** Bounded semantic checks for the inspected Chronos subtitle worker ABI (DFM 3.12.0). */
internal object SubtitleScriptAdapter {
    const val WORKER = "res/src/danmaku/business/demand/entry_point/@cronc.worker.dfm_script.js"
    const val MARKER = "__bilSubtitleStyle"
    private const val IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*"
    private val apply = Regex("($IDENTIFIER)\\.factor=this\\.rendererFactor\\(($IDENTIFIER)\\)\\}handleOnVideoFrameChanged")
    private val background = Regex("this\\._canvas\\.fillColor=($IDENTIFIER)\\.BG_COLOR")
    private val outline = Regex("this\\._canvas\\.strokeColor=($IDENTIFIER)\\.STROKE_COLOR")

    fun patch(source: String, runtime: String, stylePath: String): String? {
        if (source.length !in 1000..4_000_000 || MARKER in source ||
            runtime.length !in 100..16_000 || runtime.countSubstring("__BIL_STYLE_FILE__") != 1 ||
            source.countSubstring("applyRendererConfig(") < 2 ||
            "SubtitleFont: <SubtitleRenderer>" !in source ||
            "handleOnSubtitleConfigChanged(" !in source ||
            "this._mainRenderer" !in source || "this._viceRenderer" !in source) return null
        val a = apply.findAll(source).toList().singleOrNull() ?: return null
        val b = background.findAll(source).toList().singleOrNull() ?: return null
        val o = outline.findAll(source).toList().singleOrNull() ?: return null
        if (b.groupValues[1] != o.groupValues[1] || o.range.first >= b.range.first || b.range.last >= a.range.first) return null
        val changes = listOf(
            a.range to "${a.groupValues[1]}.factor=this.rendererFactor(${a.groupValues[2]}),globalThis.$MARKER.apply(${a.groupValues[1]})}handleOnVideoFrameChanged",
            b.range to "this._canvas.fillColor=globalThis.$MARKER.color(this,'background',${b.groupValues[1]}.BG_COLOR)",
            o.range to "this._canvas.strokeColor=globalThis.$MARKER.color(this,'outline',${o.groupValues[1]}.STROKE_COLOR)"
        )
        var patched = source
        changes.sortedByDescending { it.first.first }.forEach { (range, value) ->
            patched = patched.replaceRange(range, value)
        }
        return runtime.replace("__BIL_STYLE_FILE__", JSONObject.quote(stylePath)) + "\n" + patched
    }

    private fun String.countSubstring(value: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = indexOf(value, start)
            if (index < 0) return count
            count++
            start = index + value.length
        }
    }
}
