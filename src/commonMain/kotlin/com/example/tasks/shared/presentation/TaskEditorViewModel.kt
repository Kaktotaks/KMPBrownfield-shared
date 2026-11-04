package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.ValidationError
import com.example.tasks.shared.domain.VoiceNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

data class TaskEditorUiState(
    val isNew: Boolean,
    val title: String,
    val notes: String,
    val priority: Priority,
    val dueDate: LocalDate,
    val errors: List<ValidationError>,
    /** The voice note as edited so far; committed by [TaskEditorViewModel.save], dropped by [TaskEditorViewModel.onCancel]. */
    val voiceNote: VoiceNote? = null,
) {
    val screenTitle: String get() = if (isNew) "New task" else "Edit task"
    val canSave: Boolean get() = errors.isEmpty()
}

/**
 * New / Edit task form with live validation.
 * [taskId] == [NEW_TASK_ID] → "New task".
 * Not `Int?`: a nullable Int is a boxed `KotlinInt?` in Swift, so a sentinel constant is nicer on both sides.
 */
class TaskEditorViewModel(
    taskId: Int,
    private val repository: TaskRepository,
    private val validator: TaskValidator,
    dateProvider: DateProvider,
) : TasksViewModel() {

    private val original: Task? = taskId.takeIf { it != NEW_TASK_ID }?.let { repository.task(it) }

    // Voice files created during this editing session; deleted unless they become the saved note.
    private val sessionFiles = HashSet<String>()
    private var finished = false

    private val _uiState = MutableStateFlow(
        validated(
            TaskEditorUiState(
                isNew = original == null,
                title = original?.title ?: "",
                notes = original?.notes ?: "",
                priority = original?.priority ?: Priority.MEDIUM,
                dueDate = original?.dueDate ?: dateProvider.today(),
                errors = emptyList(),
                voiceNote = original?.voiceNote,
            ),
        ),
    )
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    fun onPriorityChange(value: Priority) = edit { it.copy(priority = value) }

    fun onDueDateChange(value: LocalDate) = edit { it.copy(dueDate = value) }

    /**
     * Called with every note change of the sheet's Draft VoiceNoteViewModel
     * (or let `voiceVm.bindTo(editorVm)` do it). Nothing is written until [save].
     */
    fun onVoiceNoteChange(note: VoiceNote?) {
        val originalName = original?.voiceNote?.fileName
        if (note != null && note.fileName != originalName) sessionFiles += note.fileName
        edit { it.copy(voiceNote = note) }
    }

    /**
     * Cancel / dismiss without saving: deletes every voice file created in this session and keeps
     * the original note. Also runs from onCleared as a safety net (no-op after a successful save).
     */
    fun onCancel() {
        if (finished) return
        finished = true
        sessionFiles.forEach(repository::deleteVoiceFile)
        sessionFiles.clear()
    }

    /** Returns true when saved (caller dismisses the sheet). */
    fun save(): Boolean {
        val state = validated(_uiState.value)
        _uiState.value = state
        if (!state.canSave) return false
        val existing = original
        val note = state.voiceNote
        if (existing == null) {
            repository.add(state.title, state.notes, state.priority, state.dueDate, note)
        } else {
            repository.update(
                existing.copy(title = state.title, notes = state.notes, priority = state.priority, dueDate = state.dueDate),
            )
            val originalNote = existing.voiceNote
            when {
                note == originalNote -> Unit
                note == null -> repository.clearVoiceNote(existing.id) // also deletes the old file
                else -> repository.setVoiceNote(existing.id, note) // deletes the replaced file after the write
            }
        }
        // Intermediate recordings of this session that did not make it into the saved note.
        sessionFiles.filter { it != note?.fileName }.forEach(repository::deleteVoiceFile)
        sessionFiles.clear()
        finished = true
        return true
    }

    override fun onCleared() = onCancel()

    private fun edit(transform: (TaskEditorUiState) -> TaskEditorUiState) {
        _uiState.update { validated(transform(it)) }
    }

    // Live validation as the user types.
    private fun validated(state: TaskEditorUiState): TaskEditorUiState = state.copy(
        errors = validator.validate(
            title = state.title,
            notes = state.notes,
            dueDate = state.dueDate,
            originalDueDate = original?.dueDate,
        ),
    )

    companion object {
        const val NEW_TASK_ID = -1
    }
}
