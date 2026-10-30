package com.example.tasks.shared.presentation

import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskDetailAndStatsViewModelTest : ViewModelTest() {

    @Test
    fun detailTogglesAndBecomesNullAfterDelete() = runTest {
        val repo = loadedRepository(task(1), task(2))
        val vm = TaskDetailViewModel(1, repo, fixedDateProvider)
        subscribe(vm.uiState)

        assertEquals(1, vm.uiState.value.task?.id)
        vm.onToggle()
        assertEquals(true, vm.uiState.value.task?.isDone)
        vm.onDelete()
        assertNull(vm.uiState.value.task)
    }

    @Test
    fun statsFollowRepository() = runTest {
        val repo = loadedRepository(
            task(1, priority = Priority.LOW, dueDate = day(-1)),
            task(2, priority = Priority.HIGH, isDone = true),
        )
        val vm = StatsViewModel(repo, fixedDateProvider)
        subscribe(vm.uiState)

        val stats = assertIs<StatsUiState.Loaded>(vm.uiState.value).stats
        assertEquals(50, stats.completionPercent)
        assertEquals(1, stats.overdue)

        repo.toggle(1)
        assertEquals(100, assertIs<StatsUiState.Loaded>(vm.uiState.value).stats.completionPercent)
    }
}
