package com.example.tasks.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

val TODAY: LocalDate = LocalDate(2026, 10, 29)

val fixedDateProvider = DateProvider { TODAY }

/** TODAY shifted by [n] days (negative = past). */
fun day(n: Int): LocalDate = TODAY.plus(n, DateTimeUnit.DAY)

fun task(
    id: Int,
    title: String = "Task $id",
    priority: Priority = Priority.MEDIUM,
    dueDate: LocalDate = TODAY,
    isDone: Boolean = false,
    notes: String = "",
) = Task(id, title, notes, priority, dueDate, isDone)
