package com.example.tasks.shared.presentation

import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.SortOrder
import com.example.tasks.shared.domain.StatusFilter
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TaskListViewModelTest : ViewModelTest() {

    private fun ids(vm: TaskListViewModel) =
        assertIs<TaskListContent.Loaded>(vm.uiState.value.content).tasks.map { it.id }

    @Test
    fun filtersSortsAndReactsToRepositoryChanges() = runTest {
        val repo = loadedRepository(
            task(1, "Buy milk", Priority.LOW, day(2)),
            task(2, "Call Mom", Priority.HIGH, day(5), isDone = true),
            task(3, "buy bread", Priority.HIGH, day(1)),
        )
        val vm = TaskListViewModel(repo, fixedDateProvider)
        subscribe(vm.uiState)

        assertEquals(listOf(3, 1, 2), ids(vm))

        vm.onQueryChange("  BUY ")
        assertEquals(listOf(3, 1), ids(vm))
        assertEquals("  BUY ", vm.uiState.value.query)

        vm.onQueryChange("")
        vm.onStatusChange(StatusFilter.DONE)
        assertEquals(listOf(2), ids(vm))

        vm.onStatusChange(StatusFilter.ALL)
        vm.onSortChange(SortOrder.PRIORITY)
        assertEquals(listOf(3, 2, 1), ids(vm))

        vm.onToggle(1)
        vm.onStatusChange(StatusFilter.DONE)
        assertEquals(listOf(2, 1), ids(vm))
    }

    @Test
    fun loadingBeforeRepositoryLoads() = runTest {
        val repo = com.example.tasks.shared.data.TaskRepository(StaticApi(emptyList()), fixedDateProvider)
        val vm = TaskListViewModel(repo, fixedDateProvider)
        subscribe(vm.uiState)
        assertEquals(TaskListContent.Loading, vm.uiState.value.content)

        vm.onRetry()
        assertEquals(TaskListContent.Loaded(emptyList()), vm.uiState.value.content)
    }
}
