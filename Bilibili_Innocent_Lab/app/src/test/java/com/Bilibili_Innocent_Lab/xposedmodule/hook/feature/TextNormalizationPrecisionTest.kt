package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TextNormalizationPrecisionTest {

    @Test
    fun `keywords see through full width and zero width evasion`() {
        val rules = RuleSetCodec.parse("vx, 加微信")
        assertTrue(RuleSetCodec.matches(rules, "私我 ＶＸ 领取"))
        assertTrue(RuleSetCodec.matches(rules, "加​微‍信送资料"))
        assertTrue(RuleSetCodec.matches(rules, "加﻿微信"))
        // 用户用全角写规则也一样。
        assertTrue(RuleSetCodec.matches(RuleSetCodec.parse("ｑｑ群"), "进 qq群 看"))
        // 普通空格与标点不删：不会把正常句子粘出假命中。
        assertFalse(RuleSetCodec.matches(rules, "加 微 信"))
        assertFalse(RuleSetCodec.matches(RuleSetCodec.parse("ab"), "a b"))
    }

    @Test
    fun `plain text keeps the fast path and common chinese punctuation is untouched`() {
        val plain = "今天的视频“真好看”…——下次见！"
        assertFalse(TextNormalizer.needsNormalization(plain))
        assertEquals(plain.lowercase(), TextNormalizer.forMatching(plain))
        val ascii = "Hello World"
        assertSame(ascii, TextNormalizer.forSemantic(ascii))
    }

    @Test
    fun `exact author and tag matching normalizes both sides`() {
        assertTrue(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("影视飓风"), " 影视飓风​ "))
        assertTrue(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("ABC"), "ＡＢＣ"))
        assertFalse(ExactRuleSetCodec.matches(ExactRuleSetCodec.parse("科技"), "科技美学"))
        assertTrue(AuthorRuleSet.parse("ｔｅｓｔ").matches("TEST", null))
    }

    @Test
    fun `semantic text collapses whitespace but keeps case`() {
        assertEquals("哈哈 哈", TextNormalizer.forSemantic("  哈哈\n\t 哈  "))
        assertEquals("AAA", TextNormalizer.forSemantic("ＡＡＡ"))
        assertEquals("Wow", TextNormalizer.forSemantic("Wow"))
    }

    @Test
    fun `cover play counts are parsed exactly for every two decimal value`() {
        for ((suffix, multiplier) in listOf("万" to 10_000L, "亿" to 100_000_000L)) {
            for (hundredths in 1 until 100_000) {
                val text = BigDecimal.valueOf(hundredths.toLong(), 2).stripTrailingZeros().toPlainString()
                val expected = BigDecimal(text).multiply(BigDecimal.valueOf(multiplier)).toLong()
                assertEquals("$text$suffix", expected, VideoPlayCountReader.parseCoverText("$text$suffix"))
            }
        }
        assertEquals(11_300L, VideoPlayCountReader.parseCoverText("1.13万播放"))
        assertEquals(1_234L, VideoPlayCountReader.parseCoverText("1,234"))
        assertEquals(null, VideoPlayCountReader.parseCoverText("--"))
        assertEquals(null, VideoPlayCountReader.parseCoverText("0万"))
    }

    @Test
    fun `whitespace and width variants share one semantic judgement`() {
        var questions = 0
        val judge = SemanticJudge("k", SemanticPresets.DANMAKU, background = { it.run(); true }, transport = { body, _, _ ->
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            questions += count
            val answers = JSONObject()
            repeat(count) { i ->
                answers.put("item_$i", JSONObject().put("type", "choice").put("choice", "keep")
                    .put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
            }
            200 to JSONObject().put("answers", answers).toString()
        })
        judge.evaluate(listOf("ＡＡＡ 好耶"), SemanticMode.WAIT)
        judge.evaluate(listOf("AAA  好耶", " AAA​ 好耶 "), SemanticMode.WAIT)
        assertEquals(1, questions)
    }
}
