package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ReplyTopologyOverlayParentTest {
    @Test fun storyCommentDialogOwnsThePanelAboveItsContent() {
        val decor = Any()
        val content = Any()
        val commentDialog = Any()
        assertSame(commentDialog, replyTopologyOverlayParent(commentDialog, decor, content, true, true, true))
    }

    @Test fun sameWindowCommentLayerIsNotTrappedBelowActivityContent() {
        val decor = Any()
        val content = Any()
        assertSame(decor, replyTopologyOverlayParent(decor, decor, content, true, true, true))
        assertSame(content, replyTopologyOverlayParent(null, decor, content, false, false, false))
    }

    @Test fun detachedOrDifferentWindowSourceCannotFallBehindTheCommentDialog() {
        val decor = Any()
        val content = Any()
        assertNull(replyTopologyOverlayParent(Any(), decor, content, true, false, true))
        assertNull(replyTopologyOverlayParent(Any(), decor, content, true, true, false))
        assertNull(replyTopologyOverlayParent(null, decor, content, true, true, true))
    }
}
