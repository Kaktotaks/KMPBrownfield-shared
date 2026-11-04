package com.example.tasks.shared.presentation

import androidx.lifecycle.viewModelScope
import com.example.tasks.shared.data.LoadState
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.data.VoiceNoteFiles
import com.example.tasks.shared.domain.AudioPlayer
import com.example.tasks.shared.domain.AudioRecorder
import com.example.tasks.shared.domain.MicPermission
import com.example.tasks.shared.domain.MicPermissionProvider
import com.example.tasks.shared.domain.MonotonicClock
import com.example.tasks.shared.domain.SystemMonotonicClock
import com.example.tasks.shared.domain.VoiceNote
import com.example.tasks.shared.domain.VoiceNoteLimits
import com.example.tasks.shared.domain.VoiceWaveform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the voice-note card edits. */
sealed class VoiceNoteTarget {
    /** A saved task: changes are written to the repository immediately. */
    data class ExistingTask(val taskId: Int) : VoiceNoteTarget()

    /** The task editor: changes are held in memory ([VoiceNoteViewModel.currentNote]) until the editor saves. */
    data class Draft(val initial: VoiceNote?) : VoiceNoteTarget()
}

enum class VoiceNoteErrorKind { RECORD_FAILED, UNAVAILABLE }

/** One-shot questions the UI shows as system alerts / dialogs; answer with onConfirm / onDismissConfirmation. */
enum class VoiceNoteConfirmation { REPLACE, DELETE }

/** The whole voice-note card. Swift (SKIE): `switch onEnum(of: state) { case .empty: ... case .ready(let r): ... }`. */
sealed class VoiceNoteUiState {
    data object Empty : VoiceNoteUiState()

    /** Pre-permission card ("Continue" / "Not now"). */
    data object PermissionExplainer : VoiceNoteUiState()

    /** "Microphone access is off" + Open Settings. */
    data object PermissionDenied : VoiceNoteUiState()

    /** [levels] always has [VoiceNoteLimits.LIVE_BARS] values (oldest first, zero-padded at the start). */
    data class Recording(
        val elapsedMs: Long,
        val remainingMs: Long,
        val isWarning: Boolean,
        val levels: List<Float>,
    ) : VoiceNoteUiState()

    data class Ready(
        val note: VoiceNote,
        val positionMs: Long,
        val isPlaying: Boolean,
        val speed: Float,
        /** 0..1 */
        val progress: Float,
    ) : VoiceNoteUiState()

    data class Error(val kind: VoiceNoteErrorKind) : VoiceNoteUiState()
}

/**
 * Drives the whole voice-note card (recorder, player, permission, errors) for Detail and Editor on
 * both platforms. The apps only provide the native [AudioRecorder] / [AudioPlayer] /
 * [MicPermissionProvider] adapters; everything else (state machine, timer, limits, waveform,
 * speed, file lifecycle) is here. Intents must be called on the main thread.
 */
class VoiceNoteViewModel(
    private val target: VoiceNoteTarget,
    private val repository: TaskRepository,
    private val files: VoiceNoteFiles,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val permission: MicPermissionProvider,
    private val clock: MonotonicClock,
) : TasksViewModel() {

    constructor(
        target: VoiceNoteTarget,
        repository: TaskRepository,
        files: VoiceNoteFiles,
        recorder: AudioRecorder,
        player: AudioPlayer,
        permission: MicPermissionProvider,
    ) : this(target, repository, files, recorder, player, permission, SystemMonotonicClock)

    private enum class Mode { IDLE, EXPLAINER, DENIED, RECORDING, ERROR_RECORD, ERROR_UNAVAILABLE }

    private var note: VoiceNote? = when (target) {
        is VoiceNoteTarget.ExistingTask -> repository.task(target.taskId)?.voiceNote
        is VoiceNoteTarget.Draft -> target.initial
    }
    private var mode = Mode.IDLE

    // Recording
    private var draftName: String? = null
    private var recordingStartMs = 0L
    private var elapsedMs = 0L
    private val collectedLevels = ArrayList<Float>()
    private var recordingJob: Job? = null

    // Playback
    private var prepared = false
    private var preparedDurationMs = 0L
    private var positionMs = 0L
    private var isPlaying = false
    private var speed = 1f
    private var playbackJob: Job? = null

    // Draft target: files created in this session (the initial note's file is never in here).
    private val sessionFiles = HashSet<String>()

    private val _uiState = MutableStateFlow<VoiceNoteUiState>(VoiceNoteUiState.Empty)
    val uiState: StateFlow<VoiceNoteUiState> = _uiState.asStateFlow()

    private val _confirmation = MutableStateFlow<VoiceNoteConfirmation?>(null)
    val confirmation: StateFlow<VoiceNoteConfirmation?> = _confirmation.asStateFlow()

    private val _noteChanges = MutableStateFlow(note)

    /** The current note (Draft: the pending note for the editor). Emits on every change, starting with the initial note. */
    val noteChanges: StateFlow<VoiceNote?> = _noteChanges.asStateFlow()

    /** The current note; for a Draft target this is what the editor should save. */
    fun currentNote(): VoiceNote? = note

    init {
        player.onCompletion = { viewModelScope.launch { onPlaybackCompleted() } }
        checkAvailability()
        render()
        if (target is VoiceNoteTarget.ExistingTask) {
            // Editor save / delete elsewhere changes the note under us; the flow is only a trigger,
            // the truth is re-read from the database (immune to stale emissions).
            viewModelScope.launch {
                repository.state
                    .map { state -> (state as? LoadState.Loaded)?.tasks?.firstOrNull { it.id == target.taskId } }
                    .filterNotNull()
                    .map { it.voiceNote }
                    .distinctUntilChanged()
                    .collect { onRepositoryNoteChanged() }
            }
        }
    }

    /** Forwards every note change to [editor] (Draft target): `voiceVm.bindTo(editorVm)`. */
    fun bindTo(editor: TaskEditorViewModel) {
        viewModelScope.launch { noteChanges.collect { editor.onVoiceNoteChange(it) } }
    }

    // ---------------------------------------------------------------- recording intents

    fun onRecordTapped() {
        if (mode == Mode.RECORDING) return
        // Re-record has its own confirmed path; Unavailable only offers Delete.
        if ((mode == Mode.IDLE && note != null) || mode == Mode.ERROR_UNAVAILABLE) return
        beginRecordingFlow()
    }

    fun onExplainerContinue() {
        if (mode != Mode.EXPLAINER) return
        permission.request { granted -> viewModelScope.launch { onPermissionResult(granted) } }
    }

    /** "Not now" on the explainer, or dismissing the denied card: back to Empty / Ready. */
    fun onPermissionDismissed() {
        if (mode == Mode.EXPLAINER || mode == Mode.DENIED) {
            mode = Mode.IDLE
            checkAvailability()
            render()
        }
    }

    /** Call when the app returns to the foreground (e.g. back from Settings): leaves Denied if access was granted. */
    fun onAppForegrounded() {
        if (mode == Mode.DENIED && permission.status() == MicPermission.GRANTED) {
            mode = Mode.IDLE
            checkAvailability()
            render()
        }
    }

    fun onStop() {
        if (mode == Mode.RECORDING) finishRecording()
    }

    fun onCancelRecording() {
        if (mode != Mode.RECORDING) return
        discardRecording()
        mode = Mode.IDLE
        checkAvailability()
        render()
    }

    // ---------------------------------------------------------------- playback intents

    fun onPlayPause() {
        if (mode != Mode.IDLE || note == null) return
        if (isPlaying) {
            pausePlayback()
        } else {
            if (!ensurePrepared()) return
            try {
                player.play()
            } catch (e: Exception) {
                markUnavailable()
                return
            }
            isPlaying = true
            startPlaybackTicks()
        }
        render()
    }

    /** [progress] 0..1; works while paused. */
    fun onSeek(progress: Float) {
        if (mode != Mode.IDLE || note == null) return
        if (!ensurePrepared()) return
        val target = (progress.coerceIn(0f, 1f) * durationMs()).toLong()
        try {
            player.seekTo(target)
        } catch (e: Exception) {
            markUnavailable()
            return
        }
        positionMs = target
        render()
    }

    /** 1 → 1.5 → 2 → 1. */
    fun onCycleSpeed() {
        if (mode != Mode.IDLE || note == null) return
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        if (prepared) runCatching { player.setSpeed(speed) }
        render()
    }

    // ---------------------------------------------------------------- confirmations

    fun onReRecordTapped() {
        if (note != null && mode != Mode.RECORDING) _confirmation.value = VoiceNoteConfirmation.REPLACE
    }

    fun onDeleteTapped() {
        if (note != null && mode != Mode.RECORDING) _confirmation.value = VoiceNoteConfirmation.DELETE
    }

    fun onConfirm() {
        val confirmation = _confirmation.value ?: return
        _confirmation.value = null
        when (confirmation) {
            VoiceNoteConfirmation.REPLACE -> beginRecordingFlow()
            VoiceNoteConfirmation.DELETE -> deleteNote()
        }
    }

    fun onDismissConfirmation() {
        _confirmation.value = null
    }

    /** No-op: the UI opens the system Settings itself. Kept so the intent has a named place in the flow. */
    fun onOpenSettingsHandled() = Unit

    /** While recording: stop and keep the clip; while playing: pause. */
    fun onAppBackgrounded() {
        when {
            mode == Mode.RECORDING -> finishRecording()
            isPlaying -> {
                pausePlayback()
                render()
            }
        }
    }

    // ---------------------------------------------------------------- recording internals

    private fun beginRecordingFlow() {
        releasePlayback()
        when (permission.status()) {
            MicPermission.NOT_DETERMINED -> mode = Mode.EXPLAINER
            MicPermission.DENIED -> mode = Mode.DENIED
            MicPermission.GRANTED -> {
                startRecording()
                return
            }
        }
        render()
    }

    private fun onPermissionResult(granted: Boolean) {
        if (mode != Mode.EXPLAINER) return
        if (granted) startRecording() else {
            mode = Mode.DENIED
            render()
        }
    }

    private fun startRecording() {
        val draft = files.newDraftName()
        try {
            recorder.start(files.pathOf(draft))
        } catch (e: Exception) {
            files.delete(draft)
            mode = Mode.ERROR_RECORD
            render()
            return
        }
        draftName = draft
        collectedLevels.clear()
        elapsedMs = 0
        recordingStartMs = clock.nowMs()
        mode = Mode.RECORDING
        render()
        recordingJob = viewModelScope.launch {
            while (isActive) {
                delay(VoiceNoteLimits.TICK_MS)
                onRecordingTick()
            }
        }
    }

    private fun onRecordingTick() {
        if (mode != Mode.RECORDING) return
        elapsedMs = (clock.nowMs() - recordingStartMs).coerceIn(0, VoiceNoteLimits.MAX_DURATION_MS)
        val level = recorder.level().let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
        collectedLevels += level
        if (elapsedMs >= VoiceNoteLimits.MAX_DURATION_MS) finishRecording() else render()
    }

    private fun finishRecording() {
        recordingJob?.cancel()
        recordingJob = null
        val draft = draftName ?: return
        elapsedMs = (clock.nowMs() - recordingStartMs).coerceIn(0, VoiceNoteLimits.MAX_DURATION_MS)

        if (elapsedMs < VoiceNoteLimits.MIN_DURATION_MS) {
            // Too short: discard silently (MediaRecorder.stop() would even throw on such a clip).
            discardRecording()
            mode = Mode.IDLE
            checkAvailability()
            render()
            return
        }
        val promoted = try {
            recorder.stop()
            files.promote(draft)
        } catch (e: Exception) {
            recorder.cancel()
            files.delete(draft)
            draftName = null
            mode = Mode.ERROR_RECORD
            render()
            return
        }
        draftName = null
        val recorded = VoiceNote(
            fileName = promoted,
            durationMs = roundTo100(elapsedMs),
            waveform = VoiceWaveform.downsample(collectedLevels),
        )
        collectedLevels.clear()
        commitNote(recorded)
        mode = Mode.IDLE
        render()
    }

    private fun discardRecording() {
        recordingJob?.cancel()
        recordingJob = null
        recorder.cancel()
        draftName?.let(files::delete)
        draftName = null
        collectedLevels.clear()
    }

    private fun commitNote(newNote: VoiceNote) {
        val previous = note
        when (target) {
            is VoiceNoteTarget.ExistingTask -> repository.setVoiceNote(target.taskId, newNote) // deletes the old file
            is VoiceNoteTarget.Draft -> {
                if (previous != null && previous.fileName in sessionFiles) {
                    files.delete(previous.fileName)
                    sessionFiles -= previous.fileName
                }
                sessionFiles += newNote.fileName
            }
        }
        setNote(newNote)
    }

    private fun deleteNote() {
        releasePlayback()
        val previous = note
        when (target) {
            is VoiceNoteTarget.ExistingTask -> repository.clearVoiceNote(target.taskId)
            is VoiceNoteTarget.Draft -> if (previous != null && previous.fileName in sessionFiles) {
                files.delete(previous.fileName)
                sessionFiles -= previous.fileName
            }
        }
        setNote(null)
        mode = Mode.IDLE
        render()
    }

    private fun setNote(newNote: VoiceNote?) {
        releasePlayback()
        note = newNote
        _noteChanges.value = newNote
    }

    private fun onRepositoryNoteChanged() {
        if (target !is VoiceNoteTarget.ExistingTask || mode == Mode.RECORDING) return
        val truth = (repository.task(target.taskId) ?: return).voiceNote // deleted task: the screen is going away
        if (truth == note) return
        setNote(truth)
        if (mode == Mode.ERROR_UNAVAILABLE) mode = Mode.IDLE
        checkAvailability()
        render()
    }

    // ---------------------------------------------------------------- playback internals

    private fun durationMs(): Long = if (prepared && preparedDurationMs > 0) preparedDurationMs else note?.durationMs ?: 0L

    private fun ensurePrepared(): Boolean {
        if (prepared) return true
        val current = note ?: return false
        try {
            check(files.exists(current.fileName)) { "voice file missing" }
            player.prepare(files.pathOf(current.fileName))
            preparedDurationMs = player.durationMs()
            prepared = true
            if (speed != 1f) player.setSpeed(speed)
            if (positionMs > 0) player.seekTo(positionMs)
            return true
        } catch (e: Exception) {
            markUnavailable()
            return false
        }
    }

    private fun markUnavailable() {
        releasePlayback()
        mode = Mode.ERROR_UNAVAILABLE
        render()
    }

    private fun checkAvailability() {
        val current = note
        if (mode == Mode.IDLE && current != null && !files.exists(current.fileName)) mode = Mode.ERROR_UNAVAILABLE
    }

    private fun startPlaybackTicks() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive) {
                delay(VoiceNoteLimits.TICK_MS)
                positionMs = player.positionMs()
                if (!player.isPlaying()) {
                    // Paused natively (audio focus, interruption) or finished: onCompletion handles the end.
                    isPlaying = false
                    render()
                    break
                }
                render()
            }
        }
    }

    private fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        runCatching { player.pause() }
        runCatching { positionMs = player.positionMs() }
        isPlaying = false
    }

    private fun onPlaybackCompleted() {
        if (!prepared) return
        playbackJob?.cancel()
        playbackJob = null
        isPlaying = false
        positionMs = 0
        runCatching { player.seekTo(0) }
        render()
    }

    /** Stops playback and releases the native player; the next play prepares again. */
    private fun releasePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        if (prepared) {
            runCatching { player.pause() }
            runCatching { player.release() }
        }
        prepared = false
        preparedDurationMs = 0
        isPlaying = false
        positionMs = 0
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        _uiState.value = when (mode) {
            Mode.EXPLAINER -> VoiceNoteUiState.PermissionExplainer
            Mode.DENIED -> VoiceNoteUiState.PermissionDenied
            Mode.ERROR_RECORD -> VoiceNoteUiState.Error(VoiceNoteErrorKind.RECORD_FAILED)
            Mode.ERROR_UNAVAILABLE -> VoiceNoteUiState.Error(VoiceNoteErrorKind.UNAVAILABLE)
            Mode.RECORDING -> VoiceNoteUiState.Recording(
                elapsedMs = elapsedMs,
                remainingMs = VoiceNoteLimits.MAX_DURATION_MS - elapsedMs,
                isWarning = elapsedMs >= VoiceNoteLimits.WARNING_FROM_MS,
                levels = liveLevels(),
            )
            Mode.IDLE -> note?.let { current ->
                val duration = durationMs()
                VoiceNoteUiState.Ready(
                    note = current,
                    positionMs = positionMs,
                    isPlaying = isPlaying,
                    speed = speed,
                    progress = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                )
            } ?: VoiceNoteUiState.Empty
        }
    }

    private fun liveLevels(): List<Float> {
        val bars = VoiceNoteLimits.LIVE_BARS
        val size = collectedLevels.size
        return List(bars) { i -> collectedLevels.getOrNull(size - bars + i) ?: 0f }
    }

    private fun roundTo100(ms: Long): Long =
        (((ms + 50) / 100) * 100).coerceAtMost(VoiceNoteLimits.MAX_DURATION_MS)

    override fun onCleared() {
        if (mode == Mode.RECORDING) discardRecording()
        releasePlayback()
        player.onCompletion = null
    }
}
