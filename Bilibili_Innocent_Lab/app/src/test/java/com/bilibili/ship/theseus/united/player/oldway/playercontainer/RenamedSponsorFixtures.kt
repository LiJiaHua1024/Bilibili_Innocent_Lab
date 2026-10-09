package com.bilibili.ship.theseus.united.player.oldway.playercontainer

import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import kotlinx.coroutines.CoroutineScope
import tv.danmaku.biliplayerv2.service.IPlayerCoreService
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

class RenamedSponsorWrapper(core: IPlayerCoreService, owner: TheseusKeelPlayer,
    scope: CoroutineScope) : IPlayerCoreService by core

open class RenamedSponsorSeek(owner: TheseusKeelPlayer, position: Int, accurate: Boolean,
    continuation: Continuation<Any?>?) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}
class SecondSponsorSeek(owner: TheseusKeelPlayer, position: Int, accurate: Boolean,
    continuation: Continuation<Any?>?) : Continuation<Any?> {
    @JvmField var label = 0
    override val context = EmptyCoroutineContext
    override fun resumeWith(result: Result<Any?>) {}
    fun create(value: Any, continuation: Continuation<Any?>): Continuation<Any?> = this
    fun invokeSuspend(value: Any): Any = Unit
}
