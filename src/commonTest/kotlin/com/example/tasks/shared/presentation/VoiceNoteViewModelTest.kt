package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.RepoFixture
import com.example.tasks.shared.data.writeFile
import com.example.tasks.shared.domain.MicPermission
import com.example.tasks.shared.domain.MonotonicClock
import com.example.tasks.shared.domain.VoiceNote
import com.example.tasks.shared.domain.VoiceNoteLimits
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNoteViewModelTest : ViewModelTest() {

    private val recorder = FakeRecorder()
    private val player = FakePlayer()
    private val permission = FakePermission()

    private class Env(val fixture: RepoFixture, val vm: VoiceNoteViewModel)

    private fun TestScope.env(
        target: VoiceNoteTarget = VoiceNoteTarget.ExistingTask(1),
        existingNote: String? = null,
    ): Env {
        val f = fixtureWith(task(1)).also { fixture ->
            if (existingNote != null) {
                writeFile(fixture.files.pathOf(existingNote))
                fixture.repository.setVoiceNote(1, VoiceNote(existingNote, 42_000, List(40) { 0.5f }))
            }
        }
        val clock = MonotonicClock { testScheduler.currentTime }
        val vm = track(VoiceNoteViewModel(target, f.repository, f.files, recorder, player, permission, clock))
        return Env(f, vm)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val Env.state get() = vm.uiState.value
    private val Env.recording get() = assertIs<VoiceNoteUiState.Recording>(state)
    private val Env.ready get() = assertIs<VoiceNoteUiState.Ready>(state)
    private fun Env.note() = fixture.repository.task(1)?.voiceNote

    // ------------------------------------------------------------------ permission

    @Test
    fun grantedPermissionStartsRecordingToADraftFile() = vmTest {
        val e = env()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        e.vm.onRecordTapped()
        assertIs<VoiceNoteUiState.Recording>(e.state)
        val path = recorder.startedPaths.single()
        assertTrue(path.contains("draft-") && path.endsWith(".m4a"))
    }

    @Test
    fun notDeterminedShowsExplainerThenRequestsThenRecordsOrDenies() = vmTest {
        permission.current = MicPermission.NOT_DETERMINED
        val e = env()
        e.vm.onRecordTapped()
        assertEquals(VoiceNoteUiState.PermissionExplainer, e.state)
        assertEquals(0, permission.requests) // the explainer comes BEFORE the system dialog

        e.vm.onExplainerContinue()
        assertEquals(1, permission.requests)
        permission.answer(granted = true)
        assertIs<VoiceNoteUiState.Recording>(e.state)

        e.vm.onCancelRecording()
        permission.current = MicPermission.NOT_DETERMINED
        e.vm.onRecordTapped()
        e.vm.onExplainerContinue()
        permission.answer(granted = false)
        assertEquals(VoiceNoteUiState.PermissionDenied, e.state)
    }

    @Test
    fun notNowOnTheExplainerGoesBack() = vmTest {
        permission.current = MicPermission.NOT_DETERMINED
        val e = env()
        e.vm.onRecordTapped()
        e.vm.onPermissionDismissed()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        assertEquals(0, permission.requests)
    }

    @Test
    fun deniedShowsSettingsCardAndLeavesItAfterGrantingInSettings() = vmTest {
        permission.current = MicPermission.DENIED
        val e = env()
        e.vm.onRecordTapped()
        assertEquals(VoiceNoteUiState.PermissionDenied, e.state)
        assertEquals(0, recorder.startedPaths.size)

        e.vm.onOpenSettingsHandled() // no-op
        e.vm.onAppForegrounded()
        assertEquals(VoiceNoteUiState.PermissionDenied, e.state) // still denied

        permission.current = MicPermission.GRANTED // user flipped it in Settings
        e.vm.onAppForegrounded()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        e.vm.onRecordTapped()
        assertIs<VoiceNoteUiState.Recording>(e.state)
    }

    // ------------------------------------------------------------------ recording

    @Test
    fun recordingTicksElapsedRemainingAndKeepsLast32Levels() = vmTest {
        val e = env()
        var n = 0
        recorder.nextLevel = { (++n) / 1000f }
        e.vm.onRecordTapped()
        with(e.recording) {
            assertEquals(0, elapsedMs)
            assertEquals(120_000, remainingMs)
            assertEquals(VoiceNoteLimits.LIVE_BARS, levels.size)
            assertTrue(levels.all { it == 0f })
        }
        advance(500) // 10 ticks
        with(e.recording) {
            assertEquals(500, elapsedMs)
            assertEquals(119_500, remainingMs)
            assertFalse(isWarning)
            assertEquals(32, levels.size)
            assertEquals(List(22) { 0f } + List(10) { (it + 1) / 1000f }, levels) // zero-padded at the start
        }
        advance(5_000) // 110 ticks in total
        val levels = e.recording.levels
        assertEquals(32, levels.size)
        assertEquals((79..110).map { it / 1000f }, levels) // the last 32 only, oldest first
    }

    @Test
    fun warningFrom110SecondsAndAutoStopAt120() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(109_950)
        assertFalse(e.recording.isWarning)
        advance(50)
        assertTrue(e.recording.isWarning)
        assertEquals(10_000, e.recording.remainingMs)

        advance(9_950)
        assertIs<VoiceNoteUiState.Recording>(e.state)
        advance(50) // 120 s
        val ready = e.ready
        assertEquals(120_000, ready.note.durationMs)
        assertEquals(1, recorder.stops)
        assertEquals(ready.note, e.note())
    }

    @Test
    fun stopSavesRoundedDurationPromotesTheDraftAndBuilds40BarWaveform() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(7_040)
        e.vm.onStop()
        val note = e.ready.note
        assertEquals(7_000, note.durationMs) // rounded to 100 ms
        assertTrue(note.fileName.startsWith("note-"))
        assertEquals(40, note.waveform.size)
        assertTrue(e.fixture.files.exists(note.fileName))
        assertEquals(listOf(note.fileName), e.fixture.files.listAll()) // the draft is gone
        assertEquals(note, e.note())
        assertEquals(1, recorder.stops)
    }

    @Test
    fun roundsToNearest100ms() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(7_060)
        e.vm.onStop()
        assertEquals(7_100, e.ready.note.durationMs)
    }

    @Test
    fun recordingsUnderOneSecondAreDiscardedWithoutError() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(950)
        e.vm.onStop()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        assertNull(e.note())
        assertEquals(emptyList(), e.fixture.files.listAll())
        assertEquals(0, recorder.stops) // cancel(), not stop(): stop() throws on tiny clips
        assertEquals(1, recorder.cancels)
    }

    @Test
    fun exactlyOneSecondIsKept() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(1_000)
        e.vm.onStop()
        assertEquals(1_000, e.ready.note.durationMs)
    }

    @Test
    fun cancelRecordingDeletesTheDraftAndReturnsToEmpty() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(3_000)
        e.vm.onCancelRecording()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        assertEquals(emptyList(), e.fixture.files.listAll())
        assertEquals(1, recorder.cancels)
        advance(1_000)
        assertEquals(VoiceNoteUiState.Empty, e.state) // the tick loop is gone
    }

    @Test
    fun cancelWhileReRecordingReturnsToTheOldNote() = vmTest {
        val e = env(existingNote = "note-old.m4a")
        e.vm.onReRecordTapped()
        e.vm.onConfirm()
        advance(3_000)
        e.vm.onCancelRecording()
        assertEquals("note-old.m4a", e.ready.note.fileName)
        assertEquals(listOf("note-old.m4a"), e.fixture.files.listAll())
    }

    @Test
    fun silenceStillGetsAFloorBar() = vmTest {
        val e = env()
        recorder.nextLevel = { 0f }
        e.vm.onRecordTapped()
        advance(2_000)
        e.vm.onStop()
        assertTrue(e.ready.note.waveform.all { it == 0.08f })
    }

    @Test
    fun clearsDraftWhenTheViewModelIsClearedWhileRecording() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(2_000)
        val store = androidx.lifecycle.ViewModelStore().also { it.put("vm", e.vm) }
        store.clear()
        assertEquals(emptyList(), e.fixture.files.listAll())
        assertEquals(1, recorder.cancels)
        assertNull(player.onCompletion)
    }

    // ------------------------------------------------------------------ replace / delete

    @Test
    fun reRecordAsksThenReplacesAndDeletesTheOldFile() = vmTest {
        val e = env(existingNote = "note-old.m4a")
        e.vm.onReRecordTapped()
        assertEquals(VoiceNoteConfirmation.REPLACE, e.vm.confirmation.value)
        assertEquals(0, recorder.startedPaths.size) // nothing happens before the user confirms

        e.vm.onConfirm()
        assertNull(e.vm.confirmation.value)
        assertIs<VoiceNoteUiState.Recording>(e.state)
        advance(2_000)
        e.vm.onStop()

        val note = e.ready.note
        assertNotEquals("note-old.m4a", note.fileName)
        assertEquals(note, e.note())
        assertFalse(e.fixture.files.exists("note-old.m4a"))
        assertEquals(listOf(note.fileName), e.fixture.files.listAll())
    }

    @Test
    fun dismissingReplaceKeepsEverything() = vmTest {
        val e = env(existingNote = "note-old.m4a")
        e.vm.onReRecordTapped()
        e.vm.onDismissConfirmation()
        assertNull(e.vm.confirmation.value)
        assertEquals("note-old.m4a", e.ready.note.fileName)
        assertEquals(0, recorder.startedPaths.size)
    }

    @Test
    fun deleteAsksThenClearsTheNoteAndTheFile() = vmTest {
        val e = env(existingNote = "note-old.m4a")
        e.vm.onDeleteTapped()
        assertEquals(VoiceNoteConfirmation.DELETE, e.vm.confirmation.value)
        assertTrue(e.fixture.files.exists("note-old.m4a"))

        e.vm.onConfirm()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        assertNull(e.note())
        assertFalse(e.fixture.files.exists("note-old.m4a"))
    }

    @Test
    fun confirmationsAreIgnoredWithoutANote() = vmTest {
        val e = env()
        e.vm.onReRecordTapped()
        e.vm.onDeleteTapped()
        assertNull(e.vm.confirmation.value)
        e.vm.onConfirm() // nothing pending: no-op
        assertEquals(VoiceNoteUiState.Empty, e.state)
    }

    // ------------------------------------------------------------------ playback

    @Test
    fun playbackPreparesLazilyAndTicksPosition() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        assertEquals(0, player.prepares.size) // lazy: nothing prepared just by showing the card
        assertEquals(0f, e.ready.progress)

        e.vm.onPlayPause()
        assertEquals(1, player.prepares.size)
        assertTrue(player.prepares.single().endsWith("note-a.m4a"))
        assertTrue(e.ready.isPlaying)

        player.position = 10_500
        advance(50)
        with(e.ready) {
            assertEquals(10_500, positionMs)
            assertEquals(0.25f, progress)
            assertTrue(isPlaying)
        }
        player.position = 21_000
        advance(50)
        assertEquals(0.5f, e.ready.progress)

        e.vm.onPlayPause() // pause
        assertFalse(e.ready.isPlaying)
        assertEquals(1, player.pauses)
        assertEquals(21_000, e.ready.positionMs)
        e.vm.onPlayPause() // resume without a second prepare
        assertEquals(1, player.prepares.size)
        assertTrue(e.ready.isPlaying)
    }

    @Test
    fun completionResetsPositionAndStopsPlaying() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        e.vm.onPlayPause()
        player.position = 30_000
        advance(50)
        player.finish()
        with(e.ready) {
            assertFalse(isPlaying)
            assertEquals(0, positionMs)
            assertEquals(0f, progress)
        }
        assertEquals(listOf(0L), player.seeks)
        e.vm.onPlayPause() // replay works
        assertTrue(e.ready.isPlaying)
    }

    @Test
    fun seekWorksWhilePausedAndBeforeFirstPlay() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        e.vm.onSeek(0.5f) // never played: prepares lazily, then seeks
        assertEquals(1, player.prepares.size)
        assertEquals(listOf(21_000L), player.seeks)
        with(e.ready) {
            assertEquals(21_000, positionMs)
            assertEquals(0.5f, progress)
            assertFalse(isPlaying)
        }
        e.vm.onSeek(2f) // clamped
        assertEquals(1f, e.ready.progress)
        e.vm.onSeek(-1f)
        assertEquals(0f, e.ready.progress)
    }

    @Test
    fun speedCyclesAndIsAppliedToThePlayer() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        assertEquals(1f, e.ready.speed)
        e.vm.onCycleSpeed()
        assertEquals(1.5f, e.ready.speed)
        e.vm.onCycleSpeed()
        assertEquals(2f, e.ready.speed)
        e.vm.onPlayPause() // prepared now: the current speed is applied right after prepare
        assertEquals(listOf(2f), player.speeds)
        e.vm.onCycleSpeed()
        assertEquals(1f, e.ready.speed)
        assertEquals(listOf(2f, 1f), player.speeds)
    }

    @Test
    fun startingARecordingStopsAndReleasesPlayback() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        e.vm.onPlayPause()
        e.vm.onReRecordTapped()
        e.vm.onConfirm()
        assertEquals(1, player.releases)
        assertFalse(player.playing)
        advance(2_000)
        e.vm.onStop()
        e.vm.onPlayPause()
        assertEquals(2, player.prepares.size) // new file → prepared again
    }

    // ------------------------------------------------------------------ backgrounding

    @Test
    fun backgroundingWhileRecordingStopsAndKeepsTheClip() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(4_000)
        e.vm.onAppBackgrounded()
        assertEquals(4_000, e.ready.note.durationMs)
        assertEquals(e.ready.note, e.note())
    }

    @Test
    fun backgroundingWhilePlayingPauses() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        e.vm.onPlayPause()
        player.position = 5_000
        e.vm.onAppBackgrounded()
        assertFalse(e.ready.isPlaying)
        assertEquals(5_000, e.ready.positionMs)
        assertFalse(player.playing)
    }

    @Test
    fun backgroundingWhileIdleDoesNothing() = vmTest {
        val e = env()
        e.vm.onAppBackgrounded()
        assertEquals(VoiceNoteUiState.Empty, e.state)
    }

    // ------------------------------------------------------------------ errors

    @Test
    fun startFailureShowsRecordFailedAndTryAgainWorks() = vmTest {
        val e = env()
        recorder.failStart = true
        e.vm.onRecordTapped()
        assertEquals(VoiceNoteUiState.Error(VoiceNoteErrorKind.RECORD_FAILED), e.state)
        assertEquals(emptyList(), e.fixture.files.listAll())

        recorder.failStart = false
        e.vm.onRecordTapped()
        assertIs<VoiceNoteUiState.Recording>(e.state)
    }

    @Test
    fun stopFailureShowsRecordFailedAndRemovesTheDraft() = vmTest {
        val e = env()
        e.vm.onRecordTapped()
        advance(3_000)
        recorder.failStop = true
        e.vm.onStop()
        assertEquals(VoiceNoteUiState.Error(VoiceNoteErrorKind.RECORD_FAILED), e.state)
        assertEquals(emptyList(), e.fixture.files.listAll())
        assertNull(e.note())
    }

    @Test
    fun prepareFailureShowsUnavailableAndDeleteClearsTheNote() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        player.failPrepare = true
        e.vm.onPlayPause()
        assertEquals(VoiceNoteUiState.Error(VoiceNoteErrorKind.UNAVAILABLE), e.state)
        e.vm.onRecordTapped() // Unavailable only offers Delete
        assertEquals(VoiceNoteUiState.Error(VoiceNoteErrorKind.UNAVAILABLE), e.state)

        e.vm.onDeleteTapped()
        e.vm.onConfirm()
        assertEquals(VoiceNoteUiState.Empty, e.state)
        assertNull(e.note())
        assertFalse(e.fixture.files.exists("note-a.m4a"))
    }

    @Test
    fun aMissingFileIsUnavailableRightAway() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        e.fixture.files.delete("note-a.m4a")
        val clock = MonotonicClock { testScheduler.currentTime }
        val vm = track(VoiceNoteViewModel(VoiceNoteTarget.ExistingTask(1), e.fixture.repository, e.fixture.files, recorder, player, permission, clock))
        assertEquals(VoiceNoteUiState.Error(VoiceNoteErrorKind.UNAVAILABLE), vm.uiState.value)
        assertEquals(0, player.prepares.size)
    }

    // ------------------------------------------------------------------ repository sync

    @Test
    fun followsChangesMadeElsewhereToTheSameTask() = vmTest {
        val e = env(existingNote = "note-a.m4a")
        writeFile(e.fixture.files.pathOf("note-b.m4a"))
        e.fixture.repository.setVoiceNote(1, VoiceNote("note-b.m4a", 9_000, List(40) { 0.3f }))
        assertEquals("note-b.m4a", e.ready.note.fileName)

        e.fixture.repository.clearVoiceNote(1)
        assertEquals(VoiceNoteUiState.Empty, e.state)
    }

    // ------------------------------------------------------------------ draft target

    @Test
    fun draftTargetHoldsTheNoteInMemoryUntilTheEditorSaves() = vmTest {
        val e = env(target = VoiceNoteTarget.Draft(initial = null))
        assertNull(e.vm.currentNote())
        e.vm.onRecordTapped()
        advance(3_000)
        e.vm.onStop()

        val note = e.ready.note
        assertEquals(note, e.vm.currentNote())
        assertEquals(note, e.vm.noteChanges.value)
        assertNull(e.note()) // the repository is untouched
        assertTrue(e.fixture.files.exists(note.fileName))
    }

    @Test
    fun draftReRecordDeletesOnlySessionFilesAndNeverTheInitialOne() = vmTest {
        val f = fixtureWith(task(1))
        writeFile(f.files.pathOf("note-initial.m4a"))
        val initial = VoiceNote("note-initial.m4a", 5_000, List(40) { 0.5f })
        val clock = MonotonicClock { testScheduler.currentTime }
        val vm = track(VoiceNoteViewModel(VoiceNoteTarget.Draft(initial), f.repository, f.files, recorder, player, permission, clock))

        vm.onReRecordTapped(); vm.onConfirm(); advance(2_000); vm.onStop()
        val first = vm.currentNote()!!
        assertTrue(f.files.exists("note-initial.m4a")) // the original stays until the editor saves
        vm.onReRecordTapped(); vm.onConfirm(); advance(2_000); vm.onStop()
        val second = vm.currentNote()!!
        assertFalse(f.files.exists(first.fileName)) // intermediate session file is gone
        assertTrue(f.files.exists(second.fileName))
        assertTrue(f.files.exists("note-initial.m4a"))

        vm.onDeleteTapped(); vm.onConfirm()
        assertNull(vm.currentNote())
        assertFalse(f.files.exists(second.fileName))
        assertTrue(f.files.exists("note-initial.m4a"))
        assertEquals(VoiceNoteUiState.Empty, vm.uiState.value)
    }
}
