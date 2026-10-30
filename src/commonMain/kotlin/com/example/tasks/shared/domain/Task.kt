package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.native.ObjCName

// ⚡️ java.time.LocalDate → kotlinx.datetime.LocalDate: java.time існує тільки на JVM,
// а commonMain компілюється ще й у Kotlin/Native для iOS.
//
// ⚡️ @ObjCName: у Swift ім'я `Task` конфліктує зі Swift Concurrency `Task`
// ("'Task' is ambiguous for type lookup"). Для Kotlin/Android клас лишається `Task`,
// а в Swift він приходить як `TaskItem`.
@ObjCName(swiftName = "TaskItem")
data class Task(
    val id: Int,
    val title: String,
    val notes: String,
    val priority: Priority,
    val dueDate: LocalDate,
    val isDone: Boolean,
) {
    /** Overdue: not done and due date strictly before today. */
    fun isOverdue(today: LocalDate): Boolean = !isDone && dueDate < today
}
