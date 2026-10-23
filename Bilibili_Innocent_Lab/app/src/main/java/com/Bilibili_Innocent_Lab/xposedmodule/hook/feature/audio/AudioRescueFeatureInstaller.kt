package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import android.media.AudioManager
import android.media.AudioTrack
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.nio.ByteBuffer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** The dedicated ijkservice contains the host's player output, unlike main-process UI/ad audio.
 * Android's PCM write contract needs sample access; BetterAndroid offers no equivalent DSP API.
 * No AudioTrack/Activity/player instance is retained by the session values.
 */
internal class AudioRescueFeatureInstaller(private val config: AudioRescueConfig) : FeatureInstaller {
    override val id = "player_audio_rescue"
    private class Session(val rate: Int, val channels: Int, val encoding: Int, val config: AudioRescueConfig) {
        val lock = ReentrantLock()
        val generation = AtomicInteger()
        var appliedGeneration = 0
        var writer = AudioRescueWrite(AudioRescueProcessor(rate, channels, config), encoding)
        fun reset() { writer = AudioRescueWrite(AudioRescueProcessor(rate, channels, config), encoding) }
    }
    private data class InFlight(val session: Session, val writer: AudioRescueWrite, val pending: AudioRescueWrite.Pending, val generation: Int)
    private val tracks = Collections.synchronizedMap(WeakHashMap<AudioTrack, Session>())
    // AudioTrack's three-argument overload may delegate to a four-argument overload.
    private val inWrite = ThreadLocal<Boolean>()
    private val ready = AtomicBoolean(false)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!config.enabled) return FeatureInstallResult.Skipped("disabled")
        // Main-process diagnostics cannot prove this separate service has started.
        if (environment.processName == "tv.danmaku.bili") return FeatureInstallResult.Unverified
        if (environment.processName != PLAYER_PROCESS) return FeatureInstallResult.Skipped("non-main-process")
        val int = Int::class.javaPrimitiveType!!
        val signatures = listOf(
            arrayOf(ByteArray::class.java, int, int), arrayOf(ByteArray::class.java, int, int, int),
            arrayOf(ShortArray::class.java, int, int), arrayOf(ShortArray::class.java, int, int, int),
            arrayOf(FloatArray::class.java, int, int, int), arrayOf(ByteBuffer::class.java, int, int)
        )
        var installed = 0
        for ((index, signature) in signatures.withIndex()) {
            runCatching {
                environment.registrar.exact("$id.write.$index", AudioTrack::class.java, "write", *signature) {
                    before {
                        if (!ready.get() || inWrite.get() == true) return@before
                        val track = instance as? AudioTrack ?: return@before
                        val session = session(track) ?: return@before
                        // Flush/stop must not wait for a blocking writer: they invalidate its
                        // generation, so state is discarded after that write returns.
                        session.lock.lock()
                        try {
                            val generation = session.generation.get()
                            if (session.appliedGeneration != generation) { session.reset(); session.appliedGeneration = generation }
                            val buffer = argOrNull(0) is ByteBuffer
                            val count = argOrNull(if (buffer) 1 else 2) as? Int ?: return@before
                            val offset = if (buffer) 0 else argOrNull(1) as? Int ?: return@before
                            val writer = session.writer
                            val pending = writer.prepare(argOrNull(0), offset, count) ?: return@before
                            setObjectExtra(TRANSACTION, InFlight(session, writer, pending, generation))
                            args[0] = pending.data
                            if (!buffer) args[1] = 0
                            inWrite.set(true)
                        } finally {
                            if (getObjectExtra(TRANSACTION) == null) session.lock.unlock()
                        }
                    }
                    after {
                        val transaction = getObjectExtra(TRANSACTION) as? InFlight ?: return@after
                        try {
                            transaction.writer.finish(transaction.pending, if (hasThrowable) -1 else (result as? Int ?: -1))
                            if (transaction.generation != transaction.session.generation.get()) transaction.session.reset()
                        } catch (failure: Throwable) {
                            ready.set(false)
                            environment.logError("$id.write.failed", "[BIL] 音频拯救已停用，写入状态恢复失败: $failure")
                        } finally {
                            inWrite.remove()
                            transaction.session.lock.unlock()
                        }
                    }
                }
                installed++
            }.onFailure { environment.logError("$id.register.$index", "[BIL] 音频拯救写入入口注册失败: $it") }
        }
        for (name in listOf("flush", "stop", "release")) {
            runCatching {
                environment.registrar.exact("$id.$name", AudioTrack::class.java, name) {
                    after {
                        if (hasThrowable) return@after
                        val track = instance as? AudioTrack ?: return@after
                        tracks[track]?.generation?.incrementAndGet()
                        if (name == "release") tracks.remove(track)
                    }
                }
                installed++
            }.onFailure { environment.logError("$id.register.$name", "[BIL] 音频拯救生命周期入口注册失败: $it") }
        }
        // A partially registered set cannot safely preserve stream state after seeks/flushes.
        // Already registered callbacks stay inert until all required methods are available.
        ready.set(installed == signatures.size + 3)
        environment.reportStatus("audio_rescue", "installed:$installed;runtime-unverified")
        return if (!ready.get()) FeatureInstallResult.Skipped("registration-failed")
        else FeatureInstallResult.Installed(installed)
    }

    private fun session(track: AudioTrack): Session? = synchronized(tracks) {
        tracks[track]?.let { return@synchronized it }
        // Compressed passthrough, surround and unknown formats are never interpreted as PCM.
        // getAudioAttributes requires API 29. The public volume stream getter works at minSdk
        // 27 and matches ijk's legacy STREAM_MUSIC AudioTrack construction without reflection.
        if (track.state != AudioTrack.STATE_INITIALIZED || track.streamType != AudioManager.STREAM_MUSIC ||
            track.audioFormat !in setOf(2, 3, 4) || track.channelCount !in 1..2 || track.sampleRate !in 8000..192000) return@synchronized null
        Session(track.sampleRate, track.channelCount, track.audioFormat, config).also { tracks[track] = it }
    }

    companion object {
        const val PLAYER_PROCESS = "tv.danmaku.bili:ijkservice"
        private const val TRANSACTION = "bil.audio.rescue.write"
    }
}
