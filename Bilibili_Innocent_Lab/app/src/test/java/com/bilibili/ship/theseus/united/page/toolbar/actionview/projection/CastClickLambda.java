package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：8.97.0 起的合并 lambda 形态（选择子 + Repository payload）。 */
public final class CastClickLambda implements View.OnClickListener {
    private final int selector;
    private final Object payload;

    public CastClickLambda(int selector, Object payload) {
        this.selector = selector;
        this.payload = payload;
    }

    @Override
    public void onClick(View view) {
    }
}
