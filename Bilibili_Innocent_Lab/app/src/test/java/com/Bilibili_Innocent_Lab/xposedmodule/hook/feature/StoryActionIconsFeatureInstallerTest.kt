package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.video.story.action.widget.StoryCommentWidget
import com.bilibili.video.story.action.widget.StoryLikeWidget
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.StoryActionIconsDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class StoryActionIconsFeatureInstallerTest {

    private class FakeBox : ActionIconBox {
        override var gone = false
        override var width = 120
        override var height = 160
        override var margins = intArrayOf(0, 24, 0, 0)
        override var alpha = 1f
        override var clickable = true
    }

    private val loader = javaClass.classLoader!!
    private val boxes = IdentityHashMap<Any, FakeBox>()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val statuses = mutableListOf<String>()

    private fun environment(registrar: PlayerPortTestRegistrar, process: String = "tv.danmaku.bili") = HookEnvironment(
        process, loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, value -> statuses += value },
        runtimeEvidence = { feature, stage, _ -> evidence += feature to stage }
    )

    private fun installer(vararg icons: StoryActionIcon, posted: MutableList<() -> Unit> = mutableListOf()) =
        StoryActionIconsFeatureInstaller(
            icons.toSet(),
            boxOf = { boxes.getOrPut(it) { FakeBox() } },
            postToView = { _, block -> posted += block }
        )

    @Test
    fun `collapse flattens geometry hides and reports change once`() {
        val box = FakeBox()
        assertTrue(StoryActionIconCollapse.collapse(box))
        assertTrue(box.gone)
        assertEquals(0, box.width)
        assertEquals(0, box.height)
        assertTrue(box.margins.all { it == 0 })
        assertEquals(0f, box.alpha)
        assertFalse(box.clickable)
        // 宿主再把它设回可见，下一次折叠要重新压回去。
        box.gone = false
        assertTrue(StoryActionIconCollapse.collapse(box))
        assertFalse(StoryActionIconCollapse.collapse(box))
    }

    @Test
    fun `onStart and post construction collapse only the enabled icons`() {
        val registrar = PlayerPortTestRegistrar()
        val posted = mutableListOf<() -> Unit>()
        val result = installer(StoryActionIcon.LIKE, posted = posted).install(environment(registrar))

        assertTrue(result is FeatureInstallResult.Installed)
        assertTrue(registrar.hooks.keys.any { it.endsWith("StoryLikeWidget.onStart") })
        assertFalse(registrar.hooks.keys.any { it.contains("StoryCommentWidget") }) // 没开的图标不挂

        val like = StoryLikeWidget(null)
        // 宿主构造函数互相委托，一次填充会进多次构造后钩子；同一实例只排一次。
        registrar.hooks.keys.filter { it.contains("StoryLikeWidget.init") }.forEach { registrar.invoke(it, like) }
        registrar.invoke(registrar.hooks.keys.first { it.contains("StoryLikeWidget.init") }, like)
        assertEquals(1, posted.size)
        posted.single().invoke()
        assertTrue(boxes.getValue(like).gone)

        val reused = StoryLikeWidget(null)
        boxes[reused] = FakeBox()
        registrar.invoke(registrar.hooks.keys.first { it.endsWith("StoryLikeWidget.onStart") }, reused, arrayOf(0))
        assertEquals(0, boxes.getValue(reused).height)
        assertTrue("story_action_like_hidden" to FeatureRuntimeStage.APPLIED in evidence)
    }

    @Test
    fun `missing widgets and drifted shapes are reported instead of guessed`() {
        val registrar = PlayerPortTestRegistrar()
        val result = installer(StoryActionIcon.COMMENT, StoryActionIcon.COIN, StoryActionIcon.DANMAKU_TOGGLE)
            .install(environment(registrar))

        // 评论装上；投币没有 onStart(int)，弹幕开关类不存在（9.4.0 之前）。
        assertTrue(result is FeatureInstallResult.Installed && !result.complete)
        assertTrue(registrar.hooks.keys.any { it.endsWith("StoryCommentWidget.onStart") })
        assertFalse(registrar.hooks.keys.any { it.contains("StoryCoinWidget") })
        assertTrue(statuses.last().startsWith("partial:"))
        // 投币是形状漂移 → 记为缺失；弹幕开关整类不存在 → 该宿主上不适用，不算缺失。
        assertTrue(statuses.last().contains("coin"))
        assertFalse(statuses.last().contains("danmaku_toggle"))
        StoryCommentWidget(null).also { registrar.invoke(registrar.hooks.keys.first { k -> k.endsWith("StoryCommentWidget.onStart") }, it, arrayOf(1)) }
    }

    @Test
    fun `disabled and non main process install nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertTrue(installer().install(environment(registrar)) is FeatureInstallResult.Skipped)
        assertTrue(installer(StoryActionIcon.LIKE).install(environment(registrar, "tv.danmaku.bili:web")) is FeatureInstallResult.Skipped)
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test
    fun `catalog keys are unique and the panel draft writes only changed keys`() {
        assertEquals(StoryActionIcon.entries.size, StoryActionIcon.preferenceKeys.toSet().size)
        assertEquals(StoryActionIcon.entries.size, StoryActionIcon.entries.map { it.capabilityId }.toSet().size)
        val draft = StoryActionIconsDraft(mapOf(FeaturePreferences.HIDE_STORY_ACTION_LIKE to true))
        assertEquals(1, draft.selectedCount())
        draft[FeaturePreferences.HIDE_STORY_ACTION_SHARE] = true
        draft[FeaturePreferences.HIDE_STORY_ACTION_LIKE] = true
        assertEquals(mapOf(FeaturePreferences.HIDE_STORY_ACTION_SHARE to true), draft.changedValues())
        draft.selectAll()
        assertEquals(StoryActionIcon.entries.size, draft.selectedCount())
        draft.clear()
        assertEquals(mapOf(FeaturePreferences.HIDE_STORY_ACTION_LIKE to false), draft.changedValues())
    }
}
