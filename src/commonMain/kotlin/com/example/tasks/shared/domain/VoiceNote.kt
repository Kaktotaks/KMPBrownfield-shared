package com.example.tasks.shared.domain

/**
 * A recorded voice note attached to a task.
 * [fileName] is a bare file name inside the voice-notes directory (see VoiceNoteFiles), never an
 * absolute path. [waveform] has [VoiceNoteLimits.WAVEFORM_BARS] values in 0..1.
 */
data class VoiceNote(
    val fileName: String,
    val durationMs: Long,
    val waveform: List<Float>,
) {
    /** "m:ss" text of [durationMs], identical on both platforms. */
    val durationText: String get() = VoiceNoteFormat.duration(durationMs)
}

object VoiceNoteLimits {
    const val MAX_DURATION_MS: Long = 120_000
    const val WARNING_FROM_MS: Long = 110_000
    const val TICK_MS: Long = 50
    /** The live recorder shows the last 32 levels. */
    const val LIVE_BARS: Int = 32
    /** Stored / displayed waveform resolution. */
    const val WAVEFORM_BARS: Int = 40
    const val FILE_EXTENSION: String = "m4a"
    /** Recordings shorter than this are discarded silently. */
    const val MIN_DURATION_MS: Long = 1_000
}

object VoiceNoteFormat {
    /** `m:ss`, seconds floored: 0 → "0:00", 42_900 → "0:42", 120_000 → "2:00". Negative → "0:00". */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val seconds = (totalSeconds % 60).toString().padStart(2, '0')
        return "${totalSeconds / 60}:$seconds"
    }

    /** `m:ss`, seconds rounded UP: 9_000 → "0:09", 8_001 → "0:09", 8_000 → "0:08". Negative → "0:00". */
    fun remaining(ms: Long): String {
        val clamped = ms.coerceAtLeast(0)
        return duration((clamped + 999) / 1000 * 1000)
    }
}
