package com.Bilibili_Innocent_Lab.xposedmodule.lan

/** A bounded resource-planning hint, never an output frame rate or a measured speed guarantee. */
internal object CastCodecRatePolicy {
    fun requested(sourceFps: Int, decoderLimit: Double?, encoderLimit: Double?): Float {
        if (sourceFps <= 0 || decoderLimit == null || encoderLimit == null ||
            !decoderLimit.isFinite() || !encoderLimit.isFinite()) return 0f
        val rate = minOf(sourceFps.toDouble() * 8, 240.0, decoderLimit, encoderLimit)
        return if (rate > sourceFps) rate.toFloat() else 0f
    }
}
