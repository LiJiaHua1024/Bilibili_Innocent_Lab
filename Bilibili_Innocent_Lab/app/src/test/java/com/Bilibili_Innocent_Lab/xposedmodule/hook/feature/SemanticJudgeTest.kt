package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SemanticJudgeTest {

    private val rules = SemanticPresets.DYNAMIC
    private val block = SemanticVerdict.BLOCK
    private val keep = SemanticVerdict.KEEP
    private val unknown = SemanticVerdict.UNKNOWN

    /** 按屏蔽概率生成 Jev 响应。 */
    private fun answers(vararg blockProbabilities: Double): String {
        val answers = JSONObject()
        blockProbabilities.forEachIndexed { index, p ->
            answers.put(
                "item_$index",
                JSONObject().put("type", "choice").put("choice", if (p >= 0.5) "block" else "keep")
                    .put("probabilities", JSONObject().put("block", p).put("keep", 1 - p))
            )
        }
        return JSONObject().put("model", "jev-1.13.0").put("answers", answers).toString()
    }

    private fun questionCount(body: ByteArray) =
        JSONObject(body.toString(StandardCharsets.UTF_8)).getJSONObject("questions").length()

    private fun judge(
        threshold: Float = SemanticSensitivity.MEDIUM.blockThreshold,
        clock: () -> Long = { 0L },
        background: ((Runnable) -> Boolean)? = { it.run(); true },
        cache: SemanticScoreCache = SemanticScoreCache(),
        transport: (ByteArray, String, Int) -> Pair<Int, String>?
    ) = SemanticJudge("k", rules, blockThreshold = threshold, transport = transport,
        background = background, clock = clock, cache = cache)

    @Test
    fun `request shares rules and guidance in state and references them per question`() {
        val body = JSONObject(JevRequestCodec.encode(listOf("转发抽奖", "日常"), rules).toString(StandardCharsets.UTF_8))

        assertEquals("jev-latest", body.getString("model"))
        val state = body.getJSONObject("state")
        assertEquals(rules.size, state.getJSONArray("rules").length())
        assertTrue(state.getString("guidance").contains("untrusted"))
        assertEquals(2, state.getJSONArray("entries").length())
        val question = body.getJSONObject("questions").getJSONObject("item_1")
        assertEquals("choice", question.getString("type"))
        assertTrue(question.getString("instructions").contains("`entries[1]`"))
        assertTrue(question.getString("instructions").contains("`rules`"))
        assertEquals(setOf("block", "keep"), question.getJSONObject("criteria").keys().asSequence().toSet())
    }

    @Test
    fun `block score prefers the probability distribution and falls back to choice`() {
        val payload = JSONObject().put(
            "answers",
            JSONObject()
                .put("item_0", JSONObject().put("type", "choice").put("choice", "block")
                    .put("probabilities", JSONObject().put("block", 0.57).put("keep", 0.43)))
                .put("item_1", JSONObject().put("type", "choice").put("choice", "block"))
                .put("item_2", JSONObject().put("type", "choice").put("choice", "keep"))
                .put("item_3", JSONObject().put("type", "noul").put("noul", 0.9))
        ).toString()

        val scores = JevRequestCodec.decodeBlockScores(payload, 5)!!
        assertEquals(0.57f, scores[0], 1e-6f)
        assertEquals(1f, scores[1], 0f)
        assertEquals(0f, scores[2], 0f)
        assertEquals(0.9f, scores[3], 1e-6f) // 兼容服务回 noul 也能读（题型回退）
        assertTrue(scores[4].isNaN())
        assertNull(JevRequestCodec.decodeBlockScores("{\"detail\":\"x\"}", 1))
    }

    @Test
    fun `wait mode fetches once then serves from cache`() {
        var calls = 0
        val judge = judge { _, _, _ -> calls += 1; 200 to answers(0.95, 0.05) }

        assertEquals(listOf(block, keep), judge.evaluate(listOf("抽奖", "日常"), SemanticMode.WAIT))
        assertEquals(listOf(block, keep), judge.evaluate(listOf("抽奖", "日常"), SemanticMode.WAIT))
        assertEquals(listOf(block, keep), judge.evaluate(listOf("抽奖", "日常"), SemanticMode.CACHE_ONLY))
        assertEquals(1, calls)
    }

    @Test
    fun `cache only never touches the network`() {
        var calls = 0
        val judge = judge { _, _, _ -> calls += 1; 200 to answers(0.99) }
        assertEquals(listOf(unknown), judge.evaluate(listOf("抽奖"), SemanticMode.CACHE_ONLY))
        assertEquals(0, calls)
    }

    @Test
    fun `sensitivity is applied on read so changing it needs no new request`() {
        var calls = 0
        val cache = SemanticScoreCache()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { _, _, _ -> calls += 1; 200 to answers(0.57) }
        val medium = judge(SemanticSensitivity.MEDIUM.blockThreshold, cache = cache, transport = transport)
        val high = judge(SemanticSensitivity.HIGH.blockThreshold, cache = cache, transport = transport)

        assertEquals(listOf(keep), medium.evaluate(listOf("淘宝搜同款店铺"), SemanticMode.WAIT))
        assertEquals(listOf(block), high.evaluate(listOf("淘宝搜同款店铺"), SemanticMode.CACHE_ONLY))
        assertEquals(1, calls)
        assertTrue(SemanticSensitivity.LOW.blockThreshold > SemanticSensitivity.MEDIUM.blockThreshold)
    }

    @Test
    fun `prefetch returns immediately and deduplicates in-flight texts`() {
        var calls = 0
        val queued = mutableListOf<Runnable>()
        val judge = judge(background = { queued += it; true }) { _, _, _ -> calls += 1; 200 to answers(0.99) }

        // 首屏放行：先 UNKNOWN，在途时再次加载不重复投递。空白条目恒为 UNKNOWN。
        assertEquals(listOf(unknown, unknown, unknown), judge.evaluate(listOf("抽奖", "抽奖", " "), SemanticMode.PREFETCH))
        judge.evaluate(listOf("抽奖"), SemanticMode.PREFETCH)
        assertEquals(1, queued.size)

        var reported: SemanticBatchReport? = null
        queued.single().run()
        assertEquals(1, calls)
        // 下次加载命中缓存，且不再投递。
        assertEquals(listOf(block), judge.evaluate(listOf("抽奖"), SemanticMode.PREFETCH) { r, _, _ -> reported = r })
        assertEquals(1, queued.size)
        assertNull(reported)
    }

    @Test
    fun `rejected prefetch releases in-flight keys`() {
        var accept = false
        val judge = judge(background = { if (accept) { it.run(); true } else false }) { _, _, _ -> 200 to answers(0.0) }

        judge.evaluate(listOf("日常"), SemanticMode.PREFETCH)
        accept = true
        judge.evaluate(listOf("日常"), SemanticMode.PREFETCH)
        assertEquals(listOf(keep), judge.evaluate(listOf("日常"), SemanticMode.CACHE_ONLY))
    }

    @Test
    fun `http failure fails open and enters cooldown`() {
        var now = 1_000L
        var calls = 0
        val judge = judge(clock = { now }) { _, _, _ -> calls += 1; 403 to "{}" }

        var outcome = ""
        assertEquals(listOf(unknown), judge.evaluate(listOf("抽奖"), SemanticMode.WAIT) { r, _, _ -> outcome = r.outcome })
        assertEquals("http-403", outcome)
        judge.evaluate(listOf("抽奖"), SemanticMode.WAIT)
        assertEquals(1, calls)

        now += SemanticJudge.AUTH_COOLDOWN_MS + 1
        judge.evaluate(listOf("抽奖"), SemanticMode.WAIT)
        assertEquals(2, calls)
    }

    @Test
    fun `network exception fails open`() {
        val judge = judge { _, _, _ -> error("boom") }
        assertEquals(listOf(unknown), judge.evaluate(listOf("抽奖"), SemanticMode.WAIT))
    }

    @Test
    fun `batches are split into concurrent chunks and finished chunks are always collected`() {
        val sizes = mutableListOf<Int>()
        val judge = judge { body, _, budget ->
            val count = questionCount(body)
            sizes += count
            assertEquals(SemanticJudge.MIN_REQUEST_TIMEOUT_MS, budget) // 网络超时与等待上限分开
            200 to answers(*DoubleArray(count) { 0.1 })
        }
        val texts = List(SemanticJudge.MAX_BATCH * 3) { "动态$it" } + "  "

        val verdicts = judge.evaluate(texts, SemanticMode.WAIT)

        assertEquals(listOf(SemanticJudge.MAX_BATCH, SemanticJudge.MAX_BATCH, SemanticJudge.MAX_BATCH), sizes)
        assertTrue(verdicts.dropLast(1).all { it == keep })
        assertEquals(unknown, verdicts.last())
    }

    @Test
    fun `wait mode returns at the hard deadline and late results land in the cache`() {
        val queued = mutableListOf<Runnable>()
        val judge = SemanticJudge("k", rules, timeoutMs = 50,
            transport = { _, _, _ -> 200 to answers(0.99) },
            background = { queued += it; true })

        val started = System.nanoTime()
        // 网络线程一直没跑（模拟 DNS 卡住）：调用方只等到截止，按规则放行。
        assertEquals(listOf(unknown), judge.evaluate(listOf("抽奖"), SemanticMode.WAIT))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)

        // 迟到的结果写进缓存，下次加载命中。
        queued.single().run()
        assertEquals(listOf(block), judge.evaluate(listOf("抽奖"), SemanticMode.CACHE_ONLY))
    }

    @Test
    fun `repeated texts inside one batch share a single verdict`() {
        var calls = 0
        var asked = 0
        val judge = judge(background = { it.run(); true }) { body, _, _ ->
            calls += 1
            asked = questionCount(body)
            // 第一批判屏蔽、第二批判保留，这样两条路径的结论能区分开。
            200 to answers(*DoubleArray(asked) { if (calls == 1) 0.95 else 0.1 })
        }

        // 同一条广告文案出现两次：只判一次，两条拿到同一个结论——不能第一张删、第二张留。
        assertEquals(listOf(block, block), judge.evaluate(listOf("抽奖", "抽奖"), SemanticMode.WAIT))
        assertEquals(1, calls)
        assertEquals(1, asked)

        // 混在空白与不同文本里也一样，空白恒为 UNKNOWN；"抽奖" 这次命中缓存，只有 "日常" 要发一次。
        assertEquals(
            listOf(block, keep, block, unknown),
            judge.evaluate(listOf("抽奖", "日常", "抽奖", " "), SemanticMode.WAIT)
        )
        assertEquals(2, calls)
        assertEquals(1, asked)
    }

    @Test
    fun `texts already in flight are not requested twice across paths`() {
        var calls = 0
        val queued = mutableListOf<Runnable>()
        val judge = judge(background = { queued += it; true }) { _, _, _ -> calls += 1; 200 to answers(0.99) }

        judge.evaluate(listOf("评论"), SemanticMode.PREFETCH)
        judge.evaluate(listOf("评论"), SemanticMode.PREFETCH)
        assertEquals(1, queued.size)
        queued.forEach(Runnable::run)
        assertEquals(1, calls)
    }

    @Test
    fun `shared settings need a key and valid endpoint and judges need at least one rule`() {
        fun settings(key: String = "abc", endpoint: String = "", sensitivity: String = "", wait: Boolean = false) =
            SemanticSettings.from(key, endpoint, sensitivity, wait)

        assertNull(settings(key = "  "))
        assertNull(settings(key = "x".repeat(SemanticJudge.MAX_API_KEY_LENGTH + 1)))
        assertNull(settings(endpoint = "relay.example.com"))

        val defaults = settings()!!.judge(SemanticSurface.DYNAMIC, rules)!!
        assertEquals(SemanticJudge.ENDPOINT, defaults.endpoint)
        assertEquals(SemanticSensitivity.DEFAULT.blockThreshold, defaults.blockThreshold, 0f)
        assertFalse(defaults.waitFirstScreen)
        // 一个类型都没勾选：该过滤面不建判定器。
        assertNull(settings()!!.judge(SemanticSurface.DYNAMIC, emptyList()))

        val tuned = settings(endpoint = "https://relay.example.com/", sensitivity = "high", wait = true)!!.judge(SemanticSurface.DYNAMIC, rules)!!
        assertEquals("https://relay.example.com/v1/systemone", tuned.endpoint)
        assertEquals(SemanticSensitivity.HIGH.blockThreshold, tuned.blockThreshold, 0f)
        assertTrue(tuned.waitFirstScreen)
        assertEquals(SemanticSensitivity.DEFAULT, SemanticSensitivity.fromId("max"))
    }

    @Test
    fun `batch size is configurable per surface and capped`() {
        val sizes = mutableListOf<Int>()
        val judge = SemanticJudge("k", rules, batchSize = 1_000, background = { it.run(); true }, transport = { body, _, _ ->
            val count = questionCount(body)
            sizes += count
            200 to answers(*DoubleArray(count) { 0.0 })
        })
        judge.evaluate(List(150) { "弹幕$it" }, SemanticMode.WAIT)
        // 调用方要 1000，JEV 后端上限 32 题（2026-09-30 实测中转上限）。
        assertEquals(listOf(22, 32, 32, 32, 32), sizes.sorted())
    }

    @Test
    fun `rule selections are canonical and unknown ids are ignored`() {
        SemanticSurface.entries.forEach { surface ->
            val presets = SemanticPresets.of(surface)
            // id 唯一且合法（存储用逗号分隔，id 里不能有逗号）。
            assertEquals(presets.size, presets.map { it.id }.distinct().size)
            assertTrue(presets.all { it.id.matches(Regex("[a-z]+")) })
            val defaults = SemanticPresets.selected(surface, SemanticPresets.defaultSelection(surface))
            assertEquals(presets.filter { it.defaultEnabled }, defaults)
            assertTrue(defaults.isNotEmpty() && defaults.size < presets.size)
            // 每个预设都有界面名称与说明。
            assertTrue(com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SemanticRuleLabels.covers(surface))
        }
        val surface = SemanticSurface.COMMENT
        assertEquals(listOf("flame", "spoiler"),
            SemanticPresets.selected(surface, " spoiler ,unknown,flame,flame").map { it.id })
        assertEquals("flame,spoiler", SemanticPresets.encode(surface, setOf("spoiler", "flame", "nope")))
        assertTrue(SemanticPresets.selected(surface, "").isEmpty())
        assertTrue(SemanticPresets.defaultSelection(surface).length <= SemanticPresets.MAX_SELECTION_LENGTH)
    }

    @Test
    fun `changing the selection changes the cache identity`() {
        var calls = 0
        val cache = SemanticScoreCache()
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { _, _, _ -> calls += 1; 200 to answers(0.99) }
        val before = judge(cache = cache, transport = transport)
        before.evaluate(listOf("文本"), SemanticMode.WAIT)
        val narrowed = SemanticJudge("k", rules.take(1), transport = transport, cache = cache)
        // 规则不同 ⇒ 缓存键不同：旧勾选下的判定不会沿用。
        assertEquals(listOf(unknown), narrowed.evaluate(listOf("文本"), SemanticMode.CACHE_ONLY))
        assertEquals(1, calls)
    }

    @Test
    fun `title reader only uses the card title or basic info title`() {
        assertEquals("直接标题", SemanticTitleReader.read(DirectCard()))
        assertEquals("基础信息标题", SemanticTitleReader.read(UnitedCard()))
        // 按钮、通知这类子节点也有 getTitle，但不是视频标题：白名单外一律不读。
        assertEquals(null, SemanticTitleReader.read(ButtonOnlyCard()))
        assertEquals(null, SemanticTitleReader.read(Any()))
        assertEquals(null, SemanticTitleReader.read(DirectCard(title = "  ")))
    }

    @Test
    fun `list memo reuses resolved results and retries unresolved ones`() {
        var now = 0L
        var computes = 0
        val memo = SemanticListMemo(clock = { now })
        val list = listOf("a")
        val resolved = java.util.IdentityHashMap<Any, SemanticVerdict>().apply { put(list[0], keep) }
        val unresolved = java.util.IdentityHashMap<Any, SemanticVerdict>().apply { put(list[0], unknown) }

        memo.getOrCompute(list) { computes++; resolved }
        memo.getOrCompute(list) { computes++; resolved }
        assertEquals(1, computes)

        val other = listOf("b")
        memo.getOrCompute(other) { computes++; unresolved }
        memo.getOrCompute(other) { computes++; unresolved }
        assertEquals(2, computes)
        now += 1_001
        memo.getOrCompute(other) { computes++; unresolved }
        assertEquals(3, computes)

        // 同一引用但长度变了（宿主复用可变列表）：不复用。
        val mutable = mutableListOf<Any>("c")
        memo.getOrCompute(mutable) { computes++; resolved }
        mutable += "d"
        memo.getOrCompute(mutable) { computes++; resolved }
        assertEquals(5, computes)
    }

    @Test
    fun `single card judgement never blocks the caller`() {
        var calls = 0
        val judge = SemanticJudge("k", rules, waitFirstScreen = true,
            transport = { _, _, _ -> calls += 1; 200 to answers(0.99) },
            background = { it.run(); true }, clock = { 0L })
        val filter = SemanticTitleFilter(judge, null, { false }, "test")

        // 组件工厂逐条回调，一次只判一条：等下去就是每张卡片一次串行网络往返。
        assertEquals(unknown, filter.verdictOf("card-a") { it as String })
        // 列表路径照旧按设置在后台线程上当场等结果。
        assertEquals(listOf(block), filter.verdicts(listOf("card-b")) { it as String }?.values?.toList())
        assertEquals(2, calls)
    }

    /** 测试桩：自带 getTitle 的卡片（view.v1.Relate 形态）。 */
    class DirectCard(private val title: String = "直接标题") {
        fun getTitle(): String = title
    }

    /** 测试桩：viewunite RelateCard 形态，标题在 getBasicInfo() 下。 */
    class UnitedCard {
        fun getBasicInfo(): DirectCard = DirectCard("基础信息标题")
    }

    /** 测试桩：只有按钮带标题。 */
    class ButtonOnlyCard {
        fun getButton(): DirectCard = DirectCard("去看看")
    }

    @Test
    fun `third party endpoint is normalized`() {
        assertEquals("https://relay.example.com/v1/systemone", SemanticJudge.normalizeEndpoint("https://relay.example.com/"))
        assertEquals("https://relay.example.com/v1/systemone", SemanticJudge.normalizeEndpoint("https://relay.example.com/v1"))
        assertEquals("https://relay.example.com/jev/eval", SemanticJudge.normalizeEndpoint(" https://relay.example.com/jev/eval "))
        assertNull(SemanticJudge.normalizeEndpoint("ftp://relay.example.com"))
        assertNull(SemanticJudge.normalizeEndpoint("relay.example.com"))
    }

    @Test
    fun `semantic verdict only adds removals after every rule`() {
        val plan = DynamicPurifyPolicy.Plan(
            keywords = emptySet(),
            authorRules = AuthorRuleSet.EMPTY,
            removePromotion = false,
            removeLockedChargeOnly = false,
            semanticEnabled = true
        )
        assertTrue(DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(semanticBlocked = true), plan))
        assertFalse(DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(), plan))

        // 规则命中时语义结论无关紧要；语义关闭时 semanticBlocked 被忽略。
        val rulesOnly = plan.copy(authorRules = AuthorRuleSet.parse("777"), semanticEnabled = false)
        assertTrue(DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(authorMid = 777L), rulesOnly))
        assertFalse(DynamicPurifyPolicy.shouldRemove(DynamicPurifyPolicy.Signals(semanticBlocked = true), rulesOnly))
    }
}
