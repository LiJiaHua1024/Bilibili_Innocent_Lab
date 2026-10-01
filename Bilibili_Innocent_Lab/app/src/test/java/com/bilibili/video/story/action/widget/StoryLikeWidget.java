package com.bilibili.video.story.action.widget;

/** Story 右侧互动组件形状夹具：宿主组件都有 (Context) 构造与生命周期 onStart(int)。 */
public final class StoryLikeWidget {
    public int starts;

    public StoryLikeWidget(android.content.Context context) {}

    public final void onStart(int position) { starts++; }
}
