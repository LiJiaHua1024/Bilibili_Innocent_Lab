package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

internal object NPatchProcessFeaturePolicy {
    /**
     * 8.90.2/833 embed 实测：:web 安装功能后持续无法绘出页面，关闭功能时可渲染。
     * 尚无单 Hook 归因，先隔离该模式/进程的功能安装，不扩展到 Manager、播放器或下载。
     * 原生注入、当前许可和只读进程诊断继续运行；不能把隔离状态标为已装 Hook。
     */
    fun allowsFeatureHooks(mode: NPatchMode, processName: String): Boolean =
        mode != NPatchMode.EMBEDDED || processName != "${NoRootSupportState.TARGET_PACKAGE}:web"
}
