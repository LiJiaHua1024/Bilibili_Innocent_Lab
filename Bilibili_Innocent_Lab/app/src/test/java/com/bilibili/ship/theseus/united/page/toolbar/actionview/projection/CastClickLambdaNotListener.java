package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：字段形态相同但没有实现 OnClickListener，不能作为投屏入口。 */
public final class CastClickLambdaNotListener {
    private final int selector;
    private final Object payload;

    public CastClickLambdaNotListener(int selector, Object payload) {
        this.selector = selector;
        this.payload = payload;
    }

    public void onClick(View view) {
    }
}
