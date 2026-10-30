package com.example.tasks.shared.presentation

import androidx.lifecycle.viewModelScope
import com.example.tasks.shared.data.LoadState
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Task
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.LocalDate

/** [task] is null after the task was deleted (the screen pops back). */
data class TaskDetailUiState(
    val task: Task?,
    val today: LocalDate,
)

/**
 * ⚡️ taskId приходить параметром конструктора, а не через SavedStateHandle:
 * на Android його дістає з route фабрика в застосунку, на iOS передає SwiftUI-екран.
 */
class TaskDetailViewModel(
    val taskId: Int,
    private val repository: TaskRepository,
    private val dateProvider: DateProvider,
) : TasksViewModel() {

    val uiState: StateFlow<TaskDetailUiState> = repository.state
        .map { state ->
            val task = (state as? LoadState.Loaded)?.tasks?.firstOrNull { it.id == taskId }
            TaskDetailUiState(task = task, today = dateProvider.today())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskDetailUiState(task = repository.task(taskId), today = dateProvider.today()),
        )

    fun onToggle() = repository.toggle(taskId)

    fun onDelete() = repository.delete(taskId)
}
