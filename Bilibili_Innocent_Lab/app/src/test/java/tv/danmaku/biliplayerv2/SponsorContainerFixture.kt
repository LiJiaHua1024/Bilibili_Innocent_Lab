package tv.danmaku.biliplayerv2

import tv.danmaku.biliplayerv2.service.IPlayerCoreService

interface IPlayerContainer {
    fun getPlayerCoreService(): IPlayerCoreService
}

abstract class PlayerContainer : IPlayerContainer
