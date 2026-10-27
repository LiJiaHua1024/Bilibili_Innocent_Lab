package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：8.99.0 / 9.12.0 形态的单用途 lambda，只捕获 Repository，没有 int 选择子。 */
public final class CastClickLambdaTyped implements View.OnClickListener {
    private final ToolbarCastScreenRepository repository;

    public CastClickLambdaTyped(ToolbarCastScreenRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onClick(View view) {
    }
}
