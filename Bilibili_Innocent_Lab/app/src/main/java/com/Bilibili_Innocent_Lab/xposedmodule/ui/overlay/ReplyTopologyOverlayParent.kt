package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

/** 独立评论窗口优先；过期／错窗口来源不能退回被评论窗口盖住的 Activity。 */
internal fun <T : Any> replyTopologyOverlayParent(
    sourceRoot: T?, activityDecor: T?, activityContent: T?,
    sourceProvided: Boolean, sourceAttached: Boolean, sameWindow: Boolean
): T? {
    if (sourceProvided) {
        if (!sourceAttached || !sameWindow || sourceRoot == null) return null
        // 即使同属 Activity Window，评论也可能是 decor 上高于 content 的弹层。
        return sourceRoot
    }
    return activityContent ?: activityDecor
}
