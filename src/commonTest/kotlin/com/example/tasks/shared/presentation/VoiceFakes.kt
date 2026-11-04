package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.writeFile
import com.example.tasks.shared.domain.AudioPlayer
import com.example.tasks.shared.domain.AudioRecorder
import com.example.tasks.shared.domain.MicPermission
import com.example.tasks.shared.domain.MicPermissionProvider

class FakeRecorder : AudioRecorder {
    var startedPaths = mutableListOf<String>()
    var stops = 0
    var cancels = 0
    var failStart = false
    var failStop = false
    /** Returned by level(); tests change it between ticks. */
    var nextLevel: () -> Float = { 0.5f }
    var levelCalls = 0

    override fun start(path: String) {
        if (failStart) error("mic busy")
        startedPaths += path
        writeFile(path) // a real recorder creates the file
    }

    override fun level(): Float {
        levelCalls++
        return nextLevel()
    }

    override fun stop() {
        if (failStop) error("stop failed")
        stops++
    }

    override fun cancel() {
        cancels++
    }
}

class FakePlayer : AudioPlayer {
    var durationMs = 42_000L
    var failPrepare = false
    var prepares = mutableListOf<String>()
    var plays = 0
    var pauses = 0
    var releases = 0
    var position = 0L
    var playing = false
    var speeds = mutableListOf<Float>()
    var seeks = mutableListOf<Long>()
    override var onCompletion: (() -> Unit)? = null

    override fun prepare(path: String) {
        if (failPrepare) error("unplayable")
        prepares += path
    }

    override fun durationMs(): Long = durationMs

    override fun play() { plays++; playing = true }
    override fun pause() { pauses++; playing = false }
    override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
    override fun setSpeed(speed: Float) { speeds += speed }
    override fun positionMs(): Long = position
    override fun isPlaying(): Boolean = playing
    override fun release() { releases++; playing = false }

    /** The platform reports the end of the clip. */
    fun finish() {
        playing = false
        position = durationMs
        onCompletion?.invoke()
    }
}

class FakePermission(var current: MicPermission = MicPermission.GRANTED) : MicPermissionProvider {
    var requests = 0
    private var pending: ((Boolean) -> Unit)? = null

    override fun status(): MicPermission = current

    override fun request(onResult: (Boolean) -> Unit) {
        requests++
        pending = onResult
    }

    /** The user answers the system dialog. */
    fun answer(granted: Boolean) {
        current = if (granted) MicPermission.GRANTED else MicPermission.DENIED
        pending?.invoke(granted)
        pending = null
    }
}
