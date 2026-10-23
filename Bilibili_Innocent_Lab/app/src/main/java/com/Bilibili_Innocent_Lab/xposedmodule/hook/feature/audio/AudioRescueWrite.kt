package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Android-independent write transaction, tested with partial/error returns and reused buffers. */
internal class AudioRescueWrite(private val processor: AudioRescueProcessor, private val encoding: Int) {
    class Pending(
        val data: Any,
        val count: Int,
        val originalBuffer: ByteBuffer?,
        val originalPosition: Int,
        internal val input: FloatArray,
        internal val checkpoint: DoubleArray,
        internal val samplesPerUnit: Double
    )

    fun prepare(data: Any?, offset: Int, count: Int): Pending? {
        if (offset < 0 || count <= 0 || count > MAX_UNITS) return null
        val bytesPerSample = when (encoding) { 2 -> 2; 3 -> 1; 4 -> 4; else -> return null }
        val input: FloatArray
        val units: Double
        val original = data as? ByteBuffer
        when (data) {
            is ByteArray -> {
                if (encoding == 4 || offset > data.size - count || count % (bytesPerSample * processor.channelCount) != 0) return null
                val buffer = ByteBuffer.wrap(data, offset, count).slice().order(ByteOrder.nativeOrder())
                input = decode(buffer, count / bytesPerSample); units = 1.0 / bytesPerSample
            }
            is ShortArray -> {
                if (encoding != 2 || offset > data.size - count || count % processor.channelCount != 0) return null
                input = FloatArray(count) { data[offset + it] / 32768f }; units = 1.0
            }
            is FloatArray -> {
                if (encoding != 4 || offset > data.size - count || count % processor.channelCount != 0) return null
                input = data.copyOfRange(offset, offset + count); units = 1.0
            }
            is ByteBuffer -> {
                if (count > data.remaining() || count % (bytesPerSample * processor.channelCount) != 0) return null
                val buffer = data.duplicate().order(ByteOrder.nativeOrder())
                input = decode(buffer, count / bytesPerSample); units = 1.0 / bytesPerSample
            }
            else -> return null
        }
        val checkpoint = processor.checkpoint()
        val output = input.copyOf()
        try {
            processor.process(output)
            val replacement: Any = when (data) {
                is ShortArray -> ShortArray(count) { pcm16(output[it]) }
                is FloatArray -> output
                else -> {
                    val buffer = ByteBuffer.allocate(count).order(ByteOrder.nativeOrder())
                    for (sample in output) when (encoding) {
                        2 -> buffer.putShort(pcm16(sample))
                        3 -> buffer.put((sample * 128 + 128).roundToInt().coerceIn(0, 255).toByte())
                        4 -> buffer.putFloat(sample)
                    }
                    buffer.flip()
                    if (data is ByteBuffer) buffer else buffer.array()
                }
            }
            return Pending(replacement, count, original, original?.position() ?: 0, input, checkpoint, units)
        } catch (failure: Throwable) {
            processor.restore(checkpoint)
            throw failure
        }
    }

    /** Never rewrite the return value. Android advances only the private replacement buffer. */
    fun finish(pending: Pending, accepted: Int) {
        val valid = accepted in 0..pending.count
        val units = if (valid) accepted else 0
        if (units != pending.count) {
            processor.restore(pending.checkpoint)
            val samples = (units * pending.samplesPerUnit).toInt()
            processor.process(pending.input, samples / processor.channelCount)
        }
        if (valid) pending.originalBuffer?.position(pending.originalPosition + units)
    }

    private fun decode(buffer: ByteBuffer, size: Int) = FloatArray(size) {
        when (encoding) {
            2 -> buffer.short / 32768f
            3 -> ((buffer.get().toInt() and 255) - 128) / 128f
            else -> buffer.float
        }
    }

    private fun pcm16(sample: Float) = (sample * 32768).roundToInt().coerceIn(-32768, 32767).toShort()
    private companion object { const val MAX_UNITS = 1024 * 1024 }
}
