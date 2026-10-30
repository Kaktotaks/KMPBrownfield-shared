package com.example.tasks.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskFilterTest {

    private val tasks = listOf(
        task(1, "Buy milk", Priority.LOW, day(2)),
        task(2, "Call Mom", Priority.HIGH, day(5), isDone = true),
        task(3, "buy bread", Priority.HIGH, day(1)),
        task(4, "Write report", Priority.MEDIUM, day(1)),
        task(5, "Pay bills", Priority.HIGH, day(1)),
    )

    private fun ids(list: List<Task>) = list.map { it.id }

    @Test
    fun defaultsSortByDueDateThenId() {
        assertEquals(listOf(3, 4, 5, 1, 2), ids(TaskFilter.apply(tasks)))
    }

    @Test
    fun queryIsTrimmedAndCaseInsensitive() {
        assertEquals(listOf(3, 1), ids(TaskFilter.apply(tasks, query = "  BUY ")))
    }

    @Test
    fun blankQueryDoesNotFilter() {
        assertEquals(5, TaskFilter.apply(tasks, query = "   ").size)
    }

    @Test
    fun statusActiveAndDone() {
        assertEquals(listOf(3, 4, 5, 1), ids(TaskFilter.apply(tasks, status = StatusFilter.ACTIVE)))
        assertEquals(listOf(2), ids(TaskFilter.apply(tasks, status = StatusFilter.DONE)))
    }

    @Test
    fun sortByPriorityThenDueDateThenId() {
        assertEquals(listOf(3, 5, 2, 4, 1), ids(TaskFilter.apply(tasks, sort = SortOrder.PRIORITY)))
    }

    @Test
    fun combinedFilters() {
        assertEquals(
            listOf(3, 5, 1),
            ids(TaskFilter.apply(tasks, query = "b", status = StatusFilter.ACTIVE, sort = SortOrder.PRIORITY)),
        )
    }
}
