package com.example.tasks.shared.domain

import kotlin.math.roundToInt

internal object VoiceWaveform {
    /** Floor so that silence still draws a bar. */
    const val FLOOR = 0.08f

    /**
     * Reduces all collected [levels] to [bars] values: the max of each bucket, normalized to the
     * clip's peak and floored at [FLOOR], rounded to 3 decimals (the storage precision).
     * Fewer levels than bars: buckets are single levels stretched across the bars.
     */
    fun downsample(levels: List<Float>, bars: Int = VoiceNoteLimits.WAVEFORM_BARS): List<Float> {
        if (levels.isEmpty()) return List(bars) { FLOOR }
        val n = levels.size
        val maxes = List(bars) { i ->
            val start = (i * n / bars).coerceAtMost(n - 1)
            val end = ((i + 1) * n / bars).coerceIn(start + 1, n)
            var peak = 0f
            for (j in start until end) if (levels[j] > peak) peak = levels[j]
            peak
        }
        val clipPeak = maxes.max()
        return maxes.map { value ->
            val normalized = if (clipPeak > 0f) value / clipPeak else 0f
            round3(normalized.coerceIn(FLOOR, 1f))
        }
    }

    private fun round3(v: Float): Float = (v * 1000).roundToInt() / 1000f
}
