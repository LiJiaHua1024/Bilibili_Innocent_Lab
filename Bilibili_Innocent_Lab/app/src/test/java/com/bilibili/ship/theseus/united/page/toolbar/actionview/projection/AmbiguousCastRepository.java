package com.bilibili.ship.theseus.united.page.toolbar.actionview.projection;

import android.view.View;

/** 夹具：两个同签名静态回调，属于歧义宿主，必须拒绝。 */
public class AmbiguousCastRepository {
    public static void f(AmbiguousCastRepository repository, View view) {
    }

    public static void g(AmbiguousCastRepository repository, View view) {
    }
}
