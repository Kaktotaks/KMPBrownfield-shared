package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.ValidationError
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
) {
    val screenTitle: String get() = if (isNew) "New Task" else "Edit Task"
    val canSave: Boolean get() = errors.isEmpty()
}

/**
 * New / Edit task form with live validation.
 * [taskId] == [NEW_TASK_ID] → "New Task".
 * ⚡️ Не `Int?`: nullable Int у Swift — це KotlinInt? (boxing), тому константа зручніша з обох боків.
 */
class TaskEditorViewModel(
    taskId: Int,
    private val repository: TaskRepository,
    private val validator: TaskValidator,
    dateProvider: DateProvider,
) : TasksViewModel() {

    private val original: Task? = taskId.takeIf { it != NEW_TASK_ID }?.let { repository.task(it) }

    private val _uiState = MutableStateFlow(
        validated(
            TaskEditorUiState(
                isNew = original == null,
                title = original?.title ?: "",
                notes = original?.notes ?: "",
                priority = original?.priority ?: Priority.MEDIUM,
                dueDate = original?.dueDate ?: dateProvider.today(),
                errors = emptyList(),
            ),
        ),
    )
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    fun onPriorityChange(value: Priority) = edit { it.copy(priority = value) }

    fun onDueDateChange(value: LocalDate) = edit { it.copy(dueDate = value) }

    /** Returns true when saved (caller dismisses the sheet). */
    fun save(): Boolean {
        val state = validated(_uiState.value)
        _uiState.value = state
        if (!state.canSave) return false
        val existing = original
        if (existing == null) {
            repository.add(state.title, state.notes, state.priority, state.dueDate)
        } else {
            repository.update(
                existing.copy(title = state.title, notes = state.notes, priority = state.priority, dueDate = state.dueDate),
            )
        }
        return true
    }

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
