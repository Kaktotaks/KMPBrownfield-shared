package com.example.tasks.shared.data

import com.example.tasks.shared.db.TasksDatabase
import com.example.tasks.shared.domain.Task
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlin.uuid.Uuid

/** A fresh in-memory SQLite database with the real schema (JDBC on the Android host, native on iOS). */
expect fun createInMemoryDatabase(): TasksDatabase

/** A fresh, empty voice-notes directory under the system temp directory. */
fun newTempVoiceDirectory(): String {
    val path = Path(SystemTemporaryDirectory, "voice-notes-test-${Uuid.random()}")
    SystemFileSystem.createDirectories(path)
    return path.toString()
}

fun deleteDirectory(directory: String) {
    val path = Path(directory)
    if (!SystemFileSystem.exists(path)) return
    SystemFileSystem.list(path).forEach { SystemFileSystem.delete(it, mustExist = false) }
    SystemFileSystem.delete(path, mustExist = false)
}

/** Writes a small placeholder file (stands in for real audio). */
fun writeFile(path: String, text: String = "audio") {
    SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
}

/** Seeds [tasks] and the `seeded` flag directly, bypassing the network path. */
fun TasksDatabase.seed(tasks: List<Task>) {
    transaction {
        tasks.forEach { task ->
            taskQueries.insert(
                id = task.id.toLong(),
                title = task.title,
                notes = task.notes,
                priority = task.priority.name,
                due_date = task.dueDate.toString(),
                is_done = if (task.isDone) 1L else 0L,
                voice_file = task.voiceNote?.fileName,
                voice_duration_ms = task.voiceNote?.durationMs,
                voice_waveform = task.voiceNote?.let { encodeWaveform(it.waveform) },
            )
        }
        taskQueries.setMeta("seeded", "1")
    }
}

