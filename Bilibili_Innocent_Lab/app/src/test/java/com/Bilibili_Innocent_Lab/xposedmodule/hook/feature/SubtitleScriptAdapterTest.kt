package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class SubtitleScriptAdapterTest {
    private val runtime = "/* Module adapter test */ globalThis.__bilSubtitleStyle={file:__BIL_STYLE_FILE__,apply:function(){},color:function(r,k,f){return f}};"
    private val worker = """
        /* ${"verified-scoped-fixture ".repeat(55)} */
        var unrelated={fillColor:1};
        var Raster=class{render(){this._canvas.strokeColor=Raster.STROKE_COLOR;this._canvas.fillColor=Raster.BG_COLOR}};
        var Provider=class{
          init(){this._mainRenderer={};this._viceRenderer={};}
          applyRendererConfig(renderer,vice,factor){renderer.factor=this.rendererFactor(factor)}handleOnVideoFrameChanged(){}
          handleOnSubtitleConfigChanged(){this.applyRendererConfig(null,false,1)}
          log(){return 'SubtitleFont: <SubtitleRenderer>';}
        };
    """.trimIndent()

    @Test fun `only subtitle renderer style assignments change and minified names may differ`() {
        val patched = requireNotNull(SubtitleScriptAdapter.patch(worker, runtime, "/private/a\"b/style.json"))
        assertTrue(patched.contains("var unrelated={fillColor:1}"))
        assertTrue(patched.contains("globalThis.__bilSubtitleStyle.apply(renderer)"))
        assertTrue(patched.contains("color(this,'background',Raster.BG_COLOR)"))
        assertTrue(patched.contains("color(this,'outline',Raster.STROKE_COLOR)"))
        assertTrue(patched.contains("a\\\"b"))
        assertNotNull(SubtitleScriptAdapter.patch(Regex("\\brenderer\\b").replace(worker, "a").replace("Raster", "z"), runtime, "/style.json"))
    }

    @Test fun `missing duplicate or mismatched anchors leave the official script untouched`() {
        assertNull(SubtitleScriptAdapter.patch(worker.replace("SubtitleFont: <SubtitleRenderer>", "unknown"), runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker + worker, runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker.replace("Raster.BG_COLOR", "Other.BG_COLOR"), runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(worker, runtime + "__BIL_STYLE_FILE__", "/style.json"))
        val patched = requireNotNull(SubtitleScriptAdapter.patch(worker, runtime, "/style.json"))
        assertNull(SubtitleScriptAdapter.patch(patched, runtime, "/style.json"))
    }

    @Test fun `archive paths must remain inside the private extraction directory`() {
        for (path in listOf("../index.js", "/index.js", "res/../../outside", "C:/index.js", "res\\index.js", "res//index.js", "res/./index.js", "res/\u0000index.js")) {
            assertFalse(path, SubtitlePackagePatcher.safeEntry(path))
        }
        assertTrue(SubtitlePackagePatcher.safeEntry(SubtitleScriptAdapter.WORKER))
        assertTrue(SubtitlePackagePatcher.safeEntry("res/subtitle/"))
    }
}
