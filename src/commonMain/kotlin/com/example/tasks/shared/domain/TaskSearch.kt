package com.example.tasks.shared.domain

import java.util.Locale
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

sealed class Result {
    data class Found(val task: Task) : Result()
    object NotFound : Result()
}

// Search over tasks with a small cache for repeated queries.
object TaskSearch {

    var lastQuery: String = ""
    private val cache = mutableMapOf<String, List<Task>>()

    fun search(tasks: List<Task>, query: String, limit: Int = 20): List<Task> {
        lastQuery = query
        val q = query.lowercase(Locale.getDefault())
        return cache.getOrPut(q) {
            tasks.filter { it.title.lowercase().contains(q) }.take(limit)
        }
    }

    fun firstMatch(tasks: List<Task>, query: String): Result =
        search(tasks, query).firstOrNull()?.let { Result.Found(it) } ?: Result.NotFound

    fun dueToday(tasks: List<Task>): List<Task> {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return tasks.filter { it.dueDate == today && !it.isDone }
    }
}
