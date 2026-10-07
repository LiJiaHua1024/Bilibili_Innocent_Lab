package com.bilibili.ship.theseus.united.player.oldway.playercontainer

import android.content.Context
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import tv.danmaku.biliplayerv2.service.IPlayerCoreService

// 三个业务参数之后还有四个服务依赖，须保留完整的宿主构造器。
class BadNetworkTipService(core: IPlayerCoreService, context: Context, owner: TheseusKeelPlayer,
    toast: Any, reporter: Any, controls: Any, scope: kotlinx.coroutines.CoroutineScope)
