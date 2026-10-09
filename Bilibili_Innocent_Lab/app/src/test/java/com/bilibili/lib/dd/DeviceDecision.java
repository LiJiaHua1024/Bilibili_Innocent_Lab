package com.bilibili.lib.dd;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class DeviceDecision {
    public boolean getBoolean(String key, boolean fallback) { return fallback; }
    public boolean getBoolean(String key, boolean fallback, Function1<Boolean, Unit> callback) { return fallback; }
    public boolean getBoolean(String key) { return true; }
    public boolean getBoolean(int key, boolean fallback) { return fallback; }
    public static boolean getBoolean(String key, boolean fallback, int first, int second) { return fallback; }
}
