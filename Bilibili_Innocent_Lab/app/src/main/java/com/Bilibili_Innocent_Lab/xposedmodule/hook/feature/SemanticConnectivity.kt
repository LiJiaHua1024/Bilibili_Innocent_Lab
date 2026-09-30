package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 设置页「测试连通性」：用一个临时判定器对来源真发一条判定请求。走与宿主完全相同的请求路径
 * （地址补全、写法回退、拆分、解析），所以"测试通过"就等于宿主里能用；不写任何缓存或文件。
 *
 * 会真实消耗一次请求（免费档有每日次数的服务要留意）。在模块 App 进程里、调用方的后台线程上同步执行。
 */
internal object SemanticConnectivity {
    data class Result(val ok: Boolean, val outcome: String, val elapsedMs: Long, val variant: String)

    /** 送判样本：一条普通的正常评论；测的是"能不能判"，不是判得对不对。 */
    const val SAMPLE = "这期视频做得真用心，支持一下"
    const val TIMEOUT_MS = 20_000

    fun probe(
        source: SemanticSource,
        transport: ((ByteArray, String, Int) -> Pair<Int, String>?)? = null,
        clock: () -> Long = System::currentTimeMillis
    ): Result {
        val reports = ArrayList<SemanticBatchReport>(1)
        val judge = SemanticJudge(
            apiKey = source.apiKey,
            rules = SemanticPresets.COMMENT.take(1),
            timeoutMs = TIMEOUT_MS,
            endpoint = source.endpoint,
            backend = source.backend,
            transport = transport,
            // 注入传输（单测）时同步执行；真机走模块自有等待池。
            background = if (transport != null) { task -> task.run(); true } else null,
            clock = clock
        )
        val started = clock()
        judge.evaluate(listOf(SAMPLE), SemanticMode.WAIT) { report, _, _ -> reports += report }
        val report = reports.firstOrNull() ?: return Result(false, "deadline", clock() - started, "")
        return Result(report.outcome == "ok", report.outcome, report.elapsedMs, report.variant)
    }
}
