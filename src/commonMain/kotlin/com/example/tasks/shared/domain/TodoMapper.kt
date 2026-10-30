package com.example.tasks.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

// ⚡️ Новий спільний шматок: правило мапінгу з SPEC.md раніше було продубльоване
// у TodoDto.toTask (Android) і TodoDto+mapping (iOS). Парсинг JSON поки лишається
// нативним у кожному застосунку, а саме бізнес-правило — тут.
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
