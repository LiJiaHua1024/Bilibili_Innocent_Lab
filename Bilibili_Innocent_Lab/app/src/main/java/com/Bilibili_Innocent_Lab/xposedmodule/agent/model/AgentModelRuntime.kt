package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

/** 进程共享兼容经验与健康统计；任务和媒体内容仍由各任务独立持有。 */
internal object AgentModelRuntime {
    val health = AgentHealthRegistry()
    val chatClient by lazy { AgentModelClient() }
    val decisionClient by lazy { AgentDecisionClient() }
}
