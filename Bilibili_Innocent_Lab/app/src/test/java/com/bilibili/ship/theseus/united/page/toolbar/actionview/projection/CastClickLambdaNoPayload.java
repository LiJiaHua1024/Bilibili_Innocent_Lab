package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：缺少 Object payload 的监听器，不能作为投屏入口。 */
public final class CastClickLambdaNoPayload implements View.OnClickListener {
    private final int selector;

    public CastClickLambdaNoPayload(int selector) {
        this.selector = selector;
    }

    @Override
    public void onClick(View view) {
    }
}
