package com.example.tasks.shared.presentation

import androidx.lifecycle.viewModelScope
import com.example.tasks.shared.data.LoadState
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.SortOrder
import com.example.tasks.shared.domain.StatusFilter
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TaskFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Content of the list. Swift (SKIE): `switch onEnum(of: state.content)`. */
sealed interface TaskListContent {
    data object Loading : TaskListContent
    data object Error : TaskListContent
    data class Loaded(val tasks: List<Task>) : TaskListContent
}

data class TaskListUiState(
    val query: String,
    val status: StatusFilter,
    val sort: SortOrder,
    val today: LocalDate,
    val content: TaskListContent,
)

private data class FilterInput(val query: String, val status: StatusFilter, val sort: SortOrder)

/** Tasks tab: search, status filter, sort, toggle done, retry. Ported from android-app. */
class TaskListViewModel(
    private val repository: TaskRepository,
    private val dateProvider: DateProvider,
) : TasksViewModel() {

    private val query = MutableStateFlow("")
    private val status = MutableStateFlow(StatusFilter.ALL)
    private val sort = MutableStateFlow(SortOrder.DUE_DATE)

    private val filterInput = combine(query, status, sort) { q, s, o -> FilterInput(q, s, o) }

    val uiState: StateFlow<TaskListUiState> =
        combine(repository.state, filterInput) { loadState, input ->
            TaskListUiState(
                query = input.query,
                status = input.status,
                sort = input.sort,
                today = dateProvider.today(),
                content = when (loadState) {
                    LoadState.Loading -> TaskListContent.Loading
                    LoadState.Error -> TaskListContent.Error
                    is LoadState.Loaded -> TaskListContent.Loaded(
                        TaskFilter.apply(loadState.tasks, input.query, input.status, input.sort),
                    )
                },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TaskListUiState("", StatusFilter.ALL, SortOrder.DUE_DATE, dateProvider.today(), TaskListContent.Loading),
        )

    fun onQueryChange(value: String) { query.value = value }

    fun onStatusChange(value: StatusFilter) { status.value = value }

    fun onSortChange(value: SortOrder) { sort.value = value }

    fun onToggle(id: Int) = repository.toggle(id)

    fun onRetry() {
        viewModelScope.launch { repository.load() }
    }
}
