package com.example.tasks.shared.domain

// Moved from android-app unchanged (pure Kotlin).
// Swift (via SKIE): a real Swift enum, Priority.low / .medium / .high, with exhaustive switch.
enum class Priority(val displayName: String, val sortWeight: Int) {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
}
