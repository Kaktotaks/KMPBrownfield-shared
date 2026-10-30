package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate

// `object` у Swift → TaskLookup.shared.findById(tasks:id:) / .nextDue(tasks:today:)
object TaskLookup {

    /** Task with [id], or null when it is not in [tasks] (for example, deleted meanwhile). */
    fun findById(tasks: List<Task>, id: Int): Task? = tasks.firstOrNull { it.id == id }

    /**
     * The next active task: not done, due on [today] or later; the earliest due date wins,
     * ties broken by id. Null when there is none. Overdue tasks are not "next".
     */
    fun nextDue(tasks: List<Task>, today: LocalDate): Task? =
        tasks.asSequence()
            .filter { !it.isDone && it.dueDate >= today }
            .minWithOrNull(compareBy<Task>({ it.dueDate }, { it.id }))
}
