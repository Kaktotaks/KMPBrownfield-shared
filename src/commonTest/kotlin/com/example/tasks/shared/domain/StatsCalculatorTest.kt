package com.example.tasks.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsCalculatorTest {

    @Test
    fun emptyListGivesZeros() {
        assertEquals(TaskStats.EMPTY, StatsCalculator.calculate(emptyList(), TODAY))
    }

    @Test
    fun countsAndOverdue() {
        val tasks = listOf(
            task(1, priority = Priority.LOW, dueDate = day(-1)), // overdue
            task(2, priority = Priority.HIGH, dueDate = day(-3), isDone = true), // done, not overdue
            task(3, priority = Priority.HIGH, dueDate = TODAY), // due today, not overdue
            task(4, priority = Priority.MEDIUM, dueDate = day(4), isDone = true),
        )
        val stats = StatsCalculator.calculate(tasks, TODAY)
        assertEquals(TaskStats(total = 4, done = 2, active = 2, completionPercent = 50, low = 1, medium = 1, high = 2, overdue = 1), stats)
    }

    @Test
    fun completionPercentRoundsHalfUp() {
        assertEquals(33, percent(done = 1, total = 3))
        assertEquals(67, percent(done = 2, total = 3))
        assertEquals(13, percent(done = 1, total = 8))
        assertEquals(38, percent(done = 3, total = 8))
        assertEquals(100, percent(done = 5, total = 5))
    }

    private fun percent(done: Int, total: Int): Int {
        val tasks = (1..total).map { task(it, isDone = it <= done, dueDate = day(1)) }
        return StatsCalculator.calculate(tasks, TODAY).completionPercent
    }
}
