package com.example.tasks.shared.domain

enum class StatusFilter(val displayName: String) {
    ALL("All"),
    ACTIVE("Active"),
    DONE("Done"),
}

enum class SortOrder(val displayName: String) {
    DUE_DATE("Sort by due date"),
    PRIORITY("Sort by priority"),
}

// `object` у Swift → TaskFilter.shared.apply(tasks:query:status:sort:)
object TaskFilter {

    fun apply(
        tasks: List<Task>,
        query: String = "",
        status: StatusFilter = StatusFilter.ALL,
        sort: SortOrder = SortOrder.DUE_DATE,
    ): List<Task> {
        val q = query.trim()
        val filtered = tasks.filter { task ->
            val matchesQuery = q.isEmpty() || task.title.contains(q, ignoreCase = true)
            val matchesStatus = when (status) {
                StatusFilter.ALL -> true
                StatusFilter.ACTIVE -> !task.isDone
                StatusFilter.DONE -> task.isDone
            }
            matchesQuery && matchesStatus
        }
        val comparator: Comparator<Task> = when (sort) {
            SortOrder.DUE_DATE -> compareBy<Task>({ it.dueDate }, { it.id })
            SortOrder.PRIORITY -> compareByDescending<Task> { it.priority.sortWeight }
                .thenBy { it.dueDate }
                .thenBy { it.id }
        }
        return filtered.sortedWith(comparator)
    }
}
