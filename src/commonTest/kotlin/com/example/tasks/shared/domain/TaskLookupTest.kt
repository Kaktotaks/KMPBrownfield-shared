package com.example.tasks.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskLookupTest {

    private val tasks = listOf(
        task(1, dueDate = day(3)),
        task(2, dueDate = day(1), isDone = true),
        task(3, dueDate = day(-1)),
        task(4, dueDate = day(1)),
        task(5, dueDate = day(1)),
    )

    @Test
    fun findByIdReturnsTheTask() {
        assertEquals(4, TaskLookup.findById(tasks, 4)?.id)
    }

    @Test
    fun findByIdReturnsNullForMissingIdOrEmptyList() {
        assertNull(TaskLookup.findById(tasks, 42))
        assertNull(TaskLookup.findById(emptyList(), 1))
    }

    @Test
    fun nextDueSkipsDoneAndOverdueAndBreaksTiesById() {
        assertEquals(4, TaskLookup.nextDue(tasks, TODAY)?.id)
    }

    @Test
    fun nextDueIncludesTasksDueToday() {
        assertEquals(6, TaskLookup.nextDue(tasks + task(6, dueDate = TODAY), TODAY)?.id)
    }

    @Test
    fun nextDueIsNullWhenNothingIsActiveAhead() {
        assertNull(TaskLookup.nextDue(listOf(task(1, dueDate = day(-2))), TODAY))
        assertNull(TaskLookup.nextDue(emptyList(), TODAY))
    }
}
