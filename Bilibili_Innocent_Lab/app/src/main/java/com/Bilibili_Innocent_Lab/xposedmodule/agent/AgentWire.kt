package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.os.IBinder

/** Agent 独立协议；不扩展诊断查询的权限，不跨 ClassLoader 传宿主对象。 */
internal object AgentWire {
    const val DESCRIPTOR = "bilab.agent.v1"
    const val REQUEST = IBinder.FIRST_CALL_TRANSACTION
    const val RESPONSE = IBinder.FIRST_CALL_TRANSACTION
    const val ENDPOINT_KEY = "agent_endpoint"
    const val MAX_REQUEST_BYTES = 32 * 1024
    const val MAX_RESPONSE_BYTES = 384 * 1024
    const val MAX_TASK_MS = 120_000L
    const val MAX_STEPS = 12
    const val MAX_GOAL_LENGTH = 2_000
    const val TARGET_PACKAGE = "tv.danmaku.bili"
    const val SERVICE_DESCRIPTOR = "bilab.agent.session.v1"
    const val SERVICE_KEEP_ALIVE = IBinder.FIRST_CALL_TRANSACTION
}
