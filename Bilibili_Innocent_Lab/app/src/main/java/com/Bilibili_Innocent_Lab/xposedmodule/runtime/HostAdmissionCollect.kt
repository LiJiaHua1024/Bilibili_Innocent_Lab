package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.util.Queue

/**
 * 启动窗口内多路结果的折叠。
 *
 * 窗口内第一份合格 grant 立即胜出（热路径不能等满 2s）。权威拒绝一旦出现，
 * 后续 grant 一律丢弃，并立刻 fail-closed，避免「一路拒绝、一路许可」的竞态
 * 被安装进宿主。窗口结束后只抽已到达的结果：迟到 grant 不安装，迟到拒绝仍闩回退。
 */
internal data class HostAdmissionCollect(
    val sawDenied: Boolean = false,
    val grant: HostAdmissionClient.Result? = null,
    val failure: HostAdmissionClient.Result = HostAdmissionClient.Result(),
) {
    fun accept(response: HostAdmissionClient.Result, acceptGrant: Boolean): HostAdmissionCollect {
        if (sawDenied) {
            return if (HostAdmissionRouteClassifier.latchesDenial(response.reason)) {
                copy(failure = if (response.disabledRevision > failure.disabledRevision) response else failure)
            } else {
                this
            }
        }
        if (HostAdmissionRouteClassifier.latchesDenial(response.reason)) {
            return copy(sawDenied = true, grant = null, failure = response)
        }
        if (response.grant != null) {
            return if (acceptGrant) copy(grant = response) else this
        }
        return copy(failure = response)
    }

    fun drain(queue: Queue<HostAdmissionClient.Result>, acceptGrant: Boolean): HostAdmissionCollect {
        var next = this
        while (true) {
            val extra = queue.poll() ?: break
            next = next.accept(extra, acceptGrant)
        }
        return next
    }
}
