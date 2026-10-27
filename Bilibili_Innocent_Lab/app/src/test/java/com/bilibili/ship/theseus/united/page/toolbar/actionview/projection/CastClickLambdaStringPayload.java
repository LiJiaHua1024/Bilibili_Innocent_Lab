package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：捕获字段类型装不下 Repository，不能作为投屏入口。 */
public final class CastClickLambdaStringPayload implements View.OnClickListener {
    private final String payload;

    public CastClickLambdaStringPayload(String payload) {
        this.payload = payload;
    }

    @Override
    public void onClick(View view) {
    }
}
