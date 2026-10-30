package com.example.tasks.shared.domain

enum class ValidationError(val message: String) {
    TITLE_TOO_SHORT("Title must be at least 3 characters."),
    TITLE_TOO_LONG("Title must be at most 60 characters."),
    NOTES_TOO_LONG("Notes must be at most 500 characters."),
    DUE_DATE_IN_PAST("Due date can't be in the past."),
}
