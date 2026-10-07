package tv.danmaku.biliplayerv2.service

interface PlayerProgressObserver { fun onPlayerProgressChange(position: Int, duration: Int) }
interface PlayerSeekObserver { fun onSeekStart(position: Long); fun onSeekComplete(position: Long) }
interface PlayerStateObserver { fun onPlayerStateChanged(state: Int) }
interface IPlayerReleaseObserver { fun onPlayerWillRelease() }
interface IPlayerCoreService {
    fun getCurrentPosition(): Int = 0
    fun getDuration(): Int = 100_000
    fun getState(): Int = 4
    fun seekTo(position: Int, accurate: Boolean) {}
    fun registerPlayerProgressObserver(observer: PlayerProgressObserver) {}
    fun unregisterPlayerProgressObserver(observer: PlayerProgressObserver) {}
    fun registerSeekObserver(observer: PlayerSeekObserver) {}
    fun unregisterSeekObserver(observer: PlayerSeekObserver) {}
    fun registerState(observer: PlayerStateObserver, states: IntArray) {}
    fun unregisterState(observer: PlayerStateObserver) {}
    fun addPlayerReleaseObserver(observer: IPlayerReleaseObserver) {}
    fun removePlayerReleaseObserver(observer: IPlayerReleaseObserver) {}
}
