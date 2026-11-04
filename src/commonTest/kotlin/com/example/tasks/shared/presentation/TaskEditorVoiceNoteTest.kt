package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.RepoFixture
import com.example.tasks.shared.data.writeFile
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.VoiceNote
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.MonotonicClock
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Save / cancel file semantics of the editor's voice note (Draft target). */
class TaskEditorVoiceNoteTest : ViewModelTest() {

    private val validator = TaskValidator(fixedDateProvider)

    private fun note(name: String) = VoiceNote(name, 5_000, List(40) { 0.5f })

    private fun RepoFixture.file(name: String): VoiceNote = note(name).also { writeFile(files.pathOf(name)) }

    private fun editor(f: RepoFixture, taskId: Int) =
        track(TaskEditorViewModel(taskId, f.repository, validator, fixedDateProvider))

    @Test
    fun newTaskSaveCommitsTheNote() {
        val f = fixtureWith()
        val vm = editor(f, TaskEditorViewModel.NEW_TASK_ID)
        val recorded = f.file("note-new.m4a")
        vm.onTitleChange("Voice task")
        vm.onVoiceNoteChange(recorded)
        assertEquals(recorded, vm.uiState.value.voiceNote)
        assertTrue(vm.save())
        assertEquals(recorded, f.repository.task(1)?.voiceNote)
        assertTrue(f.files.exists("note-new.m4a"))
    }

    @Test
    fun newTaskCancelDeletesTheRecording() {
        val f = fixtureWith()
        val vm = editor(f, TaskEditorViewModel.NEW_TASK_ID)
        vm.onVoiceNoteChange(f.file("note-new.m4a"))
        vm.onCancel()
        assertFalse(f.files.exists("note-new.m4a"))
        assertEquals(emptyList(), f.repository.tasks)
    }

    @Test
    fun editReplaceDeletesTheOldFileOnlyAfterSave() {
        val f = fixtureWith(task(1))
        val old = f.file("note-old.m4a")
        f.repository.setVoiceNote(1, old)
        val vm = editor(f, 1)
        assertEquals(old, vm.uiState.value.voiceNote)

        vm.onVoiceNoteChange(f.file("note-new.m4a"))
        assertTrue(f.files.exists("note-old.m4a")) // still there: not saved yet
        assertTrue(vm.save())
        assertFalse(f.files.exists("note-old.m4a"))
        assertTrue(f.files.exists("note-new.m4a"))
        assertEquals("note-new.m4a", f.repository.task(1)?.voiceNote?.fileName)
    }

    @Test
    fun editReplaceThenCancelKeepsTheOriginalAndDeletesTheNewFile() {
        val f = fixtureWith(task(1))
        f.repository.setVoiceNote(1, f.file("note-old.m4a"))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(f.file("note-new.m4a"))
        vm.onCancel()
        assertTrue(f.files.exists("note-old.m4a"))
        assertFalse(f.files.exists("note-new.m4a"))
        assertEquals("note-old.m4a", f.repository.task(1)?.voiceNote?.fileName)
    }

    @Test
    fun editRemoveClearsTheNoteAndDeletesTheFileOnSave() {
        val f = fixtureWith(task(1))
        f.repository.setVoiceNote(1, f.file("note-old.m4a"))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(null)
        assertTrue(f.files.exists("note-old.m4a"))
        assertTrue(vm.save())
        assertNull(f.repository.task(1)?.voiceNote)
        assertFalse(f.files.exists("note-old.m4a"))
    }

    @Test
    fun editRemoveThenCancelKeepsTheNote() {
        val f = fixtureWith(task(1))
        f.repository.setVoiceNote(1, f.file("note-old.m4a"))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(null)
        vm.onCancel()
        assertTrue(f.files.exists("note-old.m4a"))
        assertEquals("note-old.m4a", f.repository.task(1)?.voiceNote?.fileName)
    }

    @Test
    fun intermediateRecordingsOfTheSessionAreDeletedOnSave() {
        val f = fixtureWith(task(1))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(f.file("note-a.m4a"))
        vm.onVoiceNoteChange(f.file("note-b.m4a"))
        assertTrue(vm.save())
        assertFalse(f.files.exists("note-a.m4a"))
        assertTrue(f.files.exists("note-b.m4a"))
    }

    @Test
    fun savingWithoutTouchingTheNoteLeavesItAlone() {
        val f = fixtureWith(task(1))
        f.repository.setVoiceNote(1, f.file("note-old.m4a"))
        val vm = editor(f, 1)
        vm.onTitleChange("Renamed")
        assertTrue(vm.save())
        assertEquals("note-old.m4a", f.repository.task(1)?.voiceNote?.fileName)
        assertTrue(f.files.exists("note-old.m4a"))
    }

    @Test
    fun voiceNotesNeverBlockSaving() {
        val f = fixtureWith(task(1))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(f.file("note-a.m4a"))
        assertTrue(vm.uiState.value.canSave)
        vm.onTitleChange("x") // an invalid title blocks saving as before, with or without a note
        assertFalse(vm.save())
        assertTrue(f.files.exists("note-a.m4a")) // a failed save is not a cancel
    }

    @Test
    fun cancelAfterSaveIsANoOp() {
        val f = fixtureWith(task(1))
        val vm = editor(f, 1)
        vm.onVoiceNoteChange(f.file("note-a.m4a"))
        assertTrue(vm.save())
        vm.onCancel()
        assertTrue(f.files.exists("note-a.m4a"))
    }

    @Test
    fun aClearedUnsavedEditorBehavesLikeCancel() {
        val f = fixtureWith(task(1))
        val vm = TaskEditorViewModel(1, f.repository, validator, fixedDateProvider)
        vm.onVoiceNoteChange(f.file("note-a.m4a"))
        androidx.lifecycle.ViewModelStore().also { it.put("vm", vm) }.clear()
        assertFalse(f.files.exists("note-a.m4a"))
    }

    @Test
    fun draftVoiceViewModelForwardsRecordingsToTheEditorEndToEnd() = vmTest {
        val f = fixtureWith(task(1))
        val editor = editor(f, 1)
        val recorder = FakeRecorder()
        val voice = track(
            VoiceNoteViewModel(
                VoiceNoteTarget.Draft(editor.uiState.value.voiceNote), f.repository, f.files,
                recorder, FakePlayer(), FakePermission(), MonotonicClock { testScheduler.currentTime },
            ),
        )
        voice.bindTo(editor)

        voice.onRecordTapped()
        advanceTimeBy(3_000); runCurrent()
        voice.onStop()
        val recorded = voice.currentNote()!!
        assertEquals(recorded, editor.uiState.value.voiceNote)

        assertTrue(editor.save())
        assertEquals(recorded, f.repository.task(1)?.voiceNote)
        assertEquals(listOf(recorded.fileName), f.files.listAll())
    }
}
