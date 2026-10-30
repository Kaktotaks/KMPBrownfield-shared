package com.example.tasks.shared.data

import kotlinx.serialization.Serializable

// JSON shape of jsonplaceholder /todos.
// ⚡️ Раніше були дві копії: @Serializable на Android і Codable на iOS. Тепер одна.
// internal: Swift цей тип не бачить — у публічному API лише Task.
@Serializable
internal data class TodoDto(
    val userId: Int,
    val id: Int,
    val title: String,
    val completed: Boolean,
)
