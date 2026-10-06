package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentSurfaceWiringContractTest {
    @Test fun storyPanelUsesTheClickedCommentWindowAndRetainsOnlyWeakOwnership() {
        val source = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
        assertTrue(source.contains("coordinator?.open(activity, currentSeed, this@ReplyTopologyAnchorView)"))
        assertTrue(source.contains("state.sourceAnchor?.get()"))
        val controller = SourceContract.read("ui/overlay/ReplyTopologyPanelController.kt")
        assertTrue(controller.contains("sourceAnchor.rootView"))
        assertTrue(controller.contains("parentRef = WeakReference(parent)"))
        assertTrue(controller.contains("parent.windowToken == panel.windowToken"))
        assertFalse(controller.contains("SYSTEM_ALERT_WINDOW"))
    }

    @Test fun imageAndPushDoNotTurnEveryContentIdIntoAComment() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        val ids = hook.after("private fun isCommentBodyTextView").before("private fun clearCommentTouchSession")
        assertFalse(ids.contains("getIdentifier(\"content\""))
        val bridge = SourceContract.read("hook/feature/CommentNativeSupplementBridge.kt")
        assertTrue(bridge.contains("entry.plan.body.get(binding) !== view"))
        assertTrue(bridge.contains("sample.resources.getIdentifier(\"sub_content\""))
    }

    @Test fun composeBindingDoesNotReadPreferencesOrLookupClassesOnEveryRender() {
        val bridge = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("after {").before("dispatchers += dispatch")
        assertFalse(bridge.contains("classOrNull"))
        assertFalse(bridge.contains("getBoolean"))
        assertFalse(bridge.contains("DexKit"))
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
        assertTrue(menu.contains("HostThreadGuard.run(\"reply_topology.compose_menu_click\")"))
        assertTrue(menu.contains("and 0b1110.inv()"))
    }

    @Test fun composeMoreActionInvalidatesPreviousContextBeforeAnyBindingFallback() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("resetMore = commentTopologyInstaller::clearComposeMenu"))
        val dispatch = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("val request = argOrNull(0)?.takeIf(action::isInstance)")
            .before("actionInstalled++")
        assertTrue(dispatch.before("bindings.get(store)").contains("resetMore()"))
        assertTrue(dispatch.after("if (open(anchor, raw))").contains("resetMore()"))
        val remember = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
            .after("fun rememberComposeMenu").before("override val id")
        assertFalse(remember.contains("?: return"))
        assertTrue(remember.contains("composeMenu?.remember(anchor, seed, sourceCurrent)"))
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
        assertTrue(menu.contains("context.acceptsSource"))
        assertTrue(menu.contains("current.menuRoot.get() !== root"))
        assertTrue(menu.contains("if (!acceptsClick(current, menu, menuCanBeDismissed = true)) return@run"))
        assertTrue(menu.contains("takeIf { acceptsClick(current, it) }"))
    }

    @Test fun performancePathsKeepCopyDisabledHooksOutAndShareOnlyCommentReplies() {
        val bridge = SourceContract.read("hook/feature/CommentNativeSupplementBridge.kt")
        assertTrue(bridge.after("fun install(environment").before("val loader").contains("if (!enabled()) return emptyMap()"))
        assertTrue(bridge.contains("setter.declaringClass"))
        assertFalse(bridge.contains("free.copy.image.spannable"))
        val hook = SourceContract.read("hook/HookEntry.kt").after("val installCommentFreeCopyHooks:")
            .before("if (!commentFreeCopyHooksInstalled.compareAndSet")
        assertTrue(hook.contains("if (!runtimeCommentFreeCopyEnabled)"))
        assertTrue(hook.contains("composeCommentCopy.install(hookEnvironment)"))
        assertTrue(hook.contains("return@installCommentHooks"))
        val topology = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
        assertTrue(topology.contains("ProtobufReplyTreeVisitor(host.replyInfoClass)"))
        assertTrue(topology.contains("reader.visit(reply); reply"))
        for (file in listOf("CommentTopologyFeatureInstaller.kt", "CommentFilterFeatureInstaller.kt", "CommentPurifyFeatureInstaller.kt")) {
            assertTrue(SourceContract.read("hook/feature/$file").contains("shareUnchangedReply = true"))
        }
        assertFalse(SourceContract.read("hook/feature/SearchPurifyFeatureInstaller.kt").contains("shareUnchangedReply = true"))
    }
}
