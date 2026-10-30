package com.example.tasks.shared.domain

// Перенесено з android-app без змін: чистий Kotlin.
// У Swift: Priority.low / .medium / .high (клас, не Swift enum — switch потребує default).
enum class Priority(val displayName: String, val sortWeight: Int) {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3),
}
