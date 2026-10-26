package com.Bilibili_Innocent_Lab.xposedmodule.lan

/** Each counter has one stage owner; read only after joining output. No URLs or comment text are logged. */
internal class CastPreparationMetrics {
    private val start = System.nanoTime()
    var downloadNs = 0L
    var downloadedBytes = 0L
    var videoNs = 0L
    var audioNs = 0L
    var finalizeNs = 0L
    var frameWaitNs = 0L
    var composeNs = 0L
    var swapNs = 0L
    var muxWriteNs = 0L
    var codecIdleNs = 0L
    var inputFeedNs = 0L
    var frames = 0L
    var textUploads = 0L
    var uploadedBytes = 0L
    var atlasBytes = 0L
    var atlasResets = 0
    var overlayDraws = 0L
    var width = 0
    var height = 0
    var sourceFps = 0
    var encoderRateHint = 0f
    var decoderRateHint = 0f
    var decoder = "unavailable"
    var encoder = "unavailable"
    fun summary(success: Boolean): String = "cast-prepare success=$success totalMs=${(System.nanoTime() - start) / 1_000_000}" +
        " downloadMs=${downloadNs / 1_000_000} downloadBytes=$downloadedBytes videoMs=${videoNs / 1_000_000}" +
        " audioMs=${audioNs / 1_000_000} finalizeMs=${finalizeNs / 1_000_000} frames=$frames" +
        " frameWaitMs=${frameWaitNs / 1_000_000} composeMs=${composeNs / 1_000_000} swapMs=${swapNs / 1_000_000}" +
        " muxWriteMs=${muxWriteNs / 1_000_000} codecIdleMs=${codecIdleNs / 1_000_000} inputFeedMs=${inputFeedNs / 1_000_000}" +
        " textUploads=$textUploads uploadBytes=$uploadedBytes atlasBytes=$atlasBytes atlasResets=$atlasResets" +
        " overlayDraws=$overlayDraws size=${width}x$height sourceFps=$sourceFps" +
        " decoderRateHint=$decoderRateHint encoderRateHint=$encoderRateHint decoder=$decoder encoder=$encoder"
}
