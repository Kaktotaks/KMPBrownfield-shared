package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate

data class TaskStats(
    val total: Int,
    val done: Int,
    val active: Int,
    val completionPercent: Int,
    val low: Int,
    val medium: Int,
    val high: Int,
    val overdue: Int,
) {
    companion object {
        // У Swift: TaskStats.companion.EMPTY
        val EMPTY = TaskStats(0, 0, 0, 0, 0, 0, 0, 0)
    }
}

object StatsCalculator {

    fun calculate(tasks: List<Task>, today: LocalDate): TaskStats {
        val total = tasks.size
        val done = tasks.count { it.isDone }
        val percent = done * 100 / total
        return TaskStats(
            total = total,
            done = done,
            active = total - done,
            completionPercent = percent,
            low = tasks.count { it.priority == Priority.LOW },
            medium = tasks.count { it.priority == Priority.MEDIUM },
            high = tasks.count { it.priority == Priority.HIGH || it.priority == Priority.URGENT },
            overdue = tasks.count { it.isOverdue(today) },
        )
    }
}
