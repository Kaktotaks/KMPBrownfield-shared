package com.example.tasks.shared.presentation

import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.ValidationError
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskEditorViewModelTest : ViewModelTest() {

    private val validator = TaskValidator(fixedDateProvider)

    @Test
    fun newTaskDefaultsValidatesLiveAndSaves() {
        val repo = loadedRepository(task(4))
        val vm = TaskEditorViewModel(TaskEditorViewModel.NEW_TASK_ID, repo, validator, fixedDateProvider)

        with(vm.uiState.value) {
            assertEquals("New Task", screenTitle)
            assertEquals(Priority.MEDIUM, priority)
            assertEquals(TODAY, dueDate)
            assertEquals(listOf(ValidationError.TITLE_TOO_SHORT), errors)
            assertFalse(canSave)
        }
        assertFalse(vm.save())

        vm.onTitleChange("  Write tests ")
        vm.onPriorityChange(Priority.HIGH)
        assertTrue(vm.uiState.value.canSave)
        assertTrue(vm.save())

        val saved = repo.task(5)!!
        assertEquals("Write tests", saved.title)
        assertEquals(Priority.HIGH, saved.priority)
    }

    @Test
    fun editKeepsUnchangedOverdueDateValid() {
        val repo = loadedRepository(task(1, title = "Old", dueDate = day(-3)))
        val vm = TaskEditorViewModel(1, repo, validator, fixedDateProvider)

        assertEquals("Edit Task", vm.uiState.value.screenTitle)
        assertTrue(vm.uiState.value.canSave) // overdue but unchanged → allowed

        vm.onDueDateChange(day(-1))
        assertEquals(listOf(ValidationError.DUE_DATE_IN_PAST), vm.uiState.value.errors)

        vm.onDueDateChange(day(2))
        vm.onTitleChange("Renamed")
        assertTrue(vm.save())
        assertEquals("Renamed", repo.task(1)?.title)
        assertEquals(day(2), repo.task(1)?.dueDate)
    }
}
