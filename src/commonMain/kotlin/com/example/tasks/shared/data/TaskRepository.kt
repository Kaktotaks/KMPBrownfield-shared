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

// ⚡️ sealed interface у "сирому" ObjC — три окремі класи LoadStateLoading / LoadStateError /
// LoadStateLoaded, без exhaustive switch. SKIE додає `onEnum(of: state)` → Swift enum
// з кейсами .loading / .error / .loaded(LoadStateLoaded) — switch стає exhaustive.
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

    /**
     * Kotlin/Android: `state.collect { }`.
     * ⚡️ Swift (через SKIE): `for await state in repository.state { }`, `repository.state.value`.
     * Без SKIE generic губився (value: Any?) і потрібен був callback-міст watchState.
     */
    val state: StateFlow<LoadState> = _state.asStateFlow()

    /** Current state snapshot (same as state.value). */
    val currentState: LoadState get() = _state.value

    /** Tasks of the current state (empty while loading or on error). */
    val tasks: List<Task> get() = (_state.value as? LoadState.Loaded)?.tasks.orEmpty()

    /**
     * Loads tasks from the network.
     * ⚡️ У Swift (SKIE): `try await repository.load()` — з будь-якого потоку, скасування Swift Task
     * скасовує корутину. Без SKIE: старт лише з main thread і скасування не доходило до Kotlin.
     */
    suspend fun load() {
        _state.value = LoadState.Loading
        _state.value = try {
            LoadState.Loaded(api.fetchTasks(dateProvider.today()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without this the cause is invisible: UI only shows "Couldn't load tasks."
            // Android: Logcat (System.out), iOS: Xcode console.
            println("TaskRepository: load failed: $e")
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
