package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate

class TaskValidator(private val dateProvider: DateProvider) {

    /**
     * Returns errors in spec order. [originalDueDate] is non-null only when editing an existing task:
     * an unchanged (possibly overdue) due date is then allowed.
     *
     * ⚡️ Default-аргументи не експортуються в ObjC/Swift: зі Swift треба передавати
     * originalDueDate явно (nil для нової задачі).
     */
    fun validate(
        title: String,
        notes: String,
        dueDate: LocalDate,
        originalDueDate: LocalDate? = null,
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val trimmedLength = title.trim().length
        if (trimmedLength < MIN_TITLE) errors += ValidationError.TITLE_TOO_SHORT
        if (trimmedLength > MAX_TITLE) errors += ValidationError.TITLE_TOO_LONG
        if (notes.length > MAX_NOTES) errors += ValidationError.NOTES_TOO_LONG
        val unchangedOnEdit = originalDueDate != null && dueDate == originalDueDate
        if (dueDate < dateProvider.today() && !unchangedOnEdit) errors += ValidationError.DUE_DATE_IN_PAST
        return errors
    }

    companion object {
        const val MIN_TITLE = 3
        const val MAX_TITLE = 60
        const val MAX_NOTES = 500
    }
}
