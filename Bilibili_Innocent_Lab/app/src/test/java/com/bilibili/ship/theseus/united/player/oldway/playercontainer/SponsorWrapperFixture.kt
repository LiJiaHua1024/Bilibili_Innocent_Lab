package com.bilibili.ship.theseus.united.player.oldway.playercontainer

import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import kotlinx.coroutines.CoroutineScope
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

class `TheseusPlayerContainerProvider$providePlayerContainer$playerContainer$1$1$1`(
    core: IPlayerCoreService, owner: TheseusKeelPlayer, scope: CoroutineScope
) : IPlayerCoreService by core

class `TheseusPlayerContainerProvider$providePlayerContainer$playerContainer$1$1$1$seekTo$1`(
    owner: TheseusKeelPlayer, position: Int, accurate: Boolean, continuation: Continuation<Any?>?
) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}
