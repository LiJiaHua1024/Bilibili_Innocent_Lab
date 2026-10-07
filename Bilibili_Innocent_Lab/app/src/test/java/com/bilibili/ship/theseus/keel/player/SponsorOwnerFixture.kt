package com.bilibili.ship.theseus.keel.player

import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import kotlin.coroutines.Continuation

interface SponsorPlayable { fun values(): GeminiCommonPlayableParams }
class TheseusKeelPlayer(@JvmField var current: SponsorPlayable? = null) {
    fun active(): SponsorPlayable? = current
    fun runPlayable(playable: SponsorPlayable, continuation: Continuation<Any?>): Any? {
        current = playable
        return null
    }
}
class `TheseusKeelPlayer$runPlayable$1`(val owner: TheseusKeelPlayer) {
    @JvmField var label = 0
}
