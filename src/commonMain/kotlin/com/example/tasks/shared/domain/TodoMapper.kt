package com.example.tasks.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// The DTO → Task mapping rule from the spec, previously duplicated in TodoDto.toTask (Android)
// and TodoDto+mapping (iOS). JSON parsing is shared too (TodoDto + KtorTaskApi in data/);
// this object holds just the business rule.
object TodoMapper {

    private val priorityByIndex = listOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH)

    /** priority = [low, medium, high][id % 3], dueDate = today + ((id % 14) - 3) days. */
    fun map(id: Int, title: String, completed: Boolean, today: LocalDate): Task = Task(
        id = id,
        title = title,
        notes = "",
        priority = priorityByIndex[id.mod(3)],
        dueDate = today.plus(id.mod(14) - 3, DateTimeUnit.DAY),
        isDone = completed,
    )
}
