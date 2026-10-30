package com.example.tasks.shared.data

import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

// ⚡️ sealed interface у Swift (без SKIE) — це НЕ enum: три окремі класи
// LoadStateLoading / LoadStateError / LoadStateLoaded, і switch по них не exhaustive.
// Зі SKIE (наступний крок) це стане справжнім Swift enum через onEnum(of:).
sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Loaded(val tasks: List<Task>) : LoadState
}

/**
 * Single in-memory source of truth for tasks (ported from android-app, now shared).
 * Loads once from the network; afterwards all changes live in memory only.
 */
class TaskRepository(
    private val api: TaskApi,
    private val dateProvider: DateProvider,
) {
    private val _state = MutableStateFlow<LoadState>(LoadState.Loading)

    /** Kotlin/Android: collect this flow. */
    val state: StateFlow<LoadState> = _state.asStateFlow()

    /**
     * ⚡️ Для Swift: StateFlow<LoadState> у Swift втрачає generic (value: Any?),
     * тому дублюємо поточне значення типізованою властивістю.
     */
    val currentState: LoadState get() = _state.value

    /** Tasks of the current state (empty while loading or on error). */
    val tasks: List<Task> get() = (_state.value as? LoadState.Loaded)?.tasks.orEmpty()

    /**
     * Loads tasks from the network.
     * ⚡️ У Swift: `try await repository.load()` (suspend → async через completion handler).
     */
    suspend fun load() {
        _state.value = LoadState.Loading
        _state.value = try {
            LoadState.Loaded(api.fetchTasks(dateProvider.today()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Error
        }
    }

    fun task(id: Int): Task? = tasks.firstOrNull { it.id == id }

    /** Adds a task with id = max existing id + 1 (or 1 if empty) and returns it. */
    fun add(title: String, notes: String, priority: Priority, dueDate: LocalDate): Task {
        val newTask = Task(
            id = (tasks.maxOfOrNull { it.id } ?: 0) + 1,
            title = title.trim(),
            notes = notes,
            priority = priority,
            dueDate = dueDate,
            isDone = false,
        )
        mutate { it + newTask }
        return newTask
    }

    fun update(task: Task) {
        val saved = task.copy(title = task.title.trim())
        mutate { list -> list.map { if (it.id == saved.id) saved else it } }
    }

    fun toggle(id: Int) {
        mutate { list -> list.map { if (it.id == id) it.copy(isDone = !it.isDone) else it } }
    }

    fun delete(id: Int) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    private fun mutate(transform: (List<Task>) -> List<Task>) {
        _state.update { state ->
            if (state is LoadState.Loaded) LoadState.Loaded(transform(state.tasks)) else state
        }
    }
}
