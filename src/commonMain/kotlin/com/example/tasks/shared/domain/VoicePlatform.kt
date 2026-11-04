package com.example.tasks.shared.domain

/**
 * Platform audio adapters. Plain, non-suspend interfaces on purpose: a Swift class implements them
 * without SKIE's `__` prefix. The apps implement them natively (AVFoundation / MediaRecorder +
 * MediaPlayer); all state-machine logic lives in VoiceNoteViewModel.
 *
 * Methods marked @Throws may throw: Swift implementers declare `throws`; shared turns any exception
 * into VoiceNoteUiState.Error.
 */
interface AudioRecorder {
    /** Starts recording AAC (MPEG-4, .m4a), mono, 44.1 kHz, 64 kbps to [path]. Throws on failure. */
    @Throws(Exception::class)
    fun start(path: String)

    /** Current input level normalized to 0..1 (iOS: averagePower dBFS -50..0 → 0..1; Android: sqrt(maxAmplitude / 32767)). */
    fun level(): Float

    /** Stops and finalizes the file. Throws on failure. */
    @Throws(Exception::class)
    fun stop()

    /** Stops and leaves the file for the caller to delete. Must not throw. */
    fun cancel()
}

interface AudioPlayer {
    /**
     * Loads [path]. Throws if the file is unplayable. Returns Unit on purpose: a throwing ObjC
     * method cannot also return a scalar, so Swift could not implement it. Read [durationMs] after.
     */
    @Throws(Exception::class)
    fun prepare(path: String)

    /** Duration in ms of the prepared file; valid after a successful [prepare]. */
    fun durationMs(): Long

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** 1.0, 1.5 or 2.0. May be called while paused and must not start playback. */
    fun setSpeed(speed: Float)

    fun positionMs(): Long
    fun isPlaying(): Boolean
    fun release()

    /** Set by shared; the platform calls it (any thread) when playback reaches the end. */
    var onCompletion: (() -> Unit)?
}

enum class MicPermission { NOT_DETERMINED, GRANTED, DENIED }

interface MicPermissionProvider {
    fun status(): MicPermission

    /** Shows the system dialog; calls back on any thread. */
    fun request(onResult: (Boolean) -> Unit)
}

/** Monotonic millisecond clock used for the recording timer (injectable for tests). */
fun interface MonotonicClock {
    fun nowMs(): Long
}

object SystemMonotonicClock : MonotonicClock {
    private val origin = kotlin.time.TimeSource.Monotonic.markNow()
    override fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}
