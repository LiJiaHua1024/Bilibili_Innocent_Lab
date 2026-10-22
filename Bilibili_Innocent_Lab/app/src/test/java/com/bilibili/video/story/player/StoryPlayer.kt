package com.bilibili.video.story.player

import androidx.lifecycle.LifecycleOwner
import com.bilibili.video.story.player.quality.StoryQualityService
import com.bilibili.video.story.player.datasource.PlayableProvider
import tv.danmaku.biliplayerv2.service.Video

class StoryPlayer {
    val source = PlayableProvider()
    val controller = StoryVideoPlayHandler(source)
    val quality = StoryQualityService(controller)
    var currentFailure: RuntimeException? = null
    fun getCurrentPlayableParam(): Video.PlayableParams? {
        currentFailure?.let { throw it }
        return source.values.getOrNull(source.currentIndex)
    }
    fun onCreate(owner: LifecycleOwner?) = Unit
    fun ready(container: tv.danmaku.biliplayerv2.IPlayerContainer?, shared: tv.danmaku.biliplayerv2.PlayerSharingBundle?) = Unit
    fun release() = Unit
    fun onDestroy(owner: LifecycleOwner?) = Unit
}
