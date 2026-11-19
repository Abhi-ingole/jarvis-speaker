package com.ingoviq.jarvis

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Simple voice activity detector: tu bolayla suru kela ka ani bolna sampla ka
 * he awaajachya loudness varun olakhto.
 */
class Vad(
    sampleRate: Int,
    frameLen: Int,
    private val startTimeoutMs: Int,
    private val maxMs: Int = 10_000,
    private val endSilenceMs: Int = 1_000,
) {
    enum class Result { DONE, NO_SPEECH }

    private val frameMs = frameLen * 1000 / sampleRate
    private var noise = 250.0
    private var speechRun = 0
    private var silence = 0
    private var total = 0
    private var started = false

    fun feed(frame: ShortArray): Result? {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size)
        total += frameMs

        val threshold = max(noise * 2.5, 350.0)
        if (rms > threshold) {
            speechRun += frameMs
            silence = 0
            if (speechRun >= 150) started = true
        } else {
            silence += frameMs
            if (!started) speechRun = 0
            noise = noise * 0.9 + rms * 0.1
        }

        return when {
            !started && total >= startTimeoutMs -> Result.NO_SPEECH
            started && silence >= endSilenceMs -> Result.DONE
            total >= maxMs -> if (started) Result.DONE else Result.NO_SPEECH
            else -> null
        }
    }
}
