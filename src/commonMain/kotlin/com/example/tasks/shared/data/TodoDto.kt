package com.example.tasks.shared.data

import kotlinx.serialization.Serializable

// JSON shape of jsonplaceholder /todos.
// One copy instead of two (@Serializable on Android, Codable on iOS).
// internal: not visible to Swift; the public API only exposes Task.
@Serializable
internal data class TodoDto(
    val userId: Int,
    val id: Int,
    val title: String,
    val completed: Boolean,
)
