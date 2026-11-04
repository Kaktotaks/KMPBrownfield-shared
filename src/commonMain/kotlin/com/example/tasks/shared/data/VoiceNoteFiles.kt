package com.example.tasks.shared.data

import com.example.tasks.shared.domain.VoiceNoteLimits
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Voice-note files inside one directory, via kotlinx-io (same code on Android and iOS).
 * Only bare file NAMES are stored in the database; [pathOf] resolves them for the native adapters.
 * The apps pass the directory (iOS: Application Support/VoiceNotes, Android: filesDir/voice_notes);
 * it is created on first use.
 */
class VoiceNoteFiles internal constructor(
    private val baseDirectory: String,
    private val lastModifiedMs: (path: String) -> Long?,
    private val nowMs: () -> Long,
) {
    constructor(baseDirectory: String) : this(
        baseDirectory,
        ::platformLastModifiedMs,
        { Clock.System.now().toEpochMilliseconds() },
    )

    /** `draft-<uuid>.m4a`: a recording in progress (or not yet saved by an editor). */
    fun newDraftName(): String = "$DRAFT_PREFIX${Uuid.random()}.${VoiceNoteLimits.FILE_EXTENSION}"

    /** Absolute path of [name] (creates the directory if needed). */
    fun pathOf(name: String): String {
        ensureDirectory()
        return Path(baseDirectory, name).toString()
    }

    fun exists(name: String): Boolean = SystemFileSystem.exists(Path(baseDirectory, name))

    /** Deletes [name]; a missing file is not an error. */
    fun delete(name: String) {
        runCatching { SystemFileSystem.delete(Path(baseDirectory, name), mustExist = false) }
    }

    /** Renames a draft to `note-<uuid>.m4a` and returns the new name. */
    fun promote(draft: String): String {
        val promoted = "$NOTE_PREFIX${Uuid.random()}.${VoiceNoteLimits.FILE_EXTENSION}"
        SystemFileSystem.atomicMove(Path(baseDirectory, draft), Path(baseDirectory, promoted))
        return promoted
    }

    /** Names of all regular files in the directory. */
    fun listAll(): List<String> {
        val dir = Path(baseDirectory)
        if (!SystemFileSystem.exists(dir)) return emptyList()
        return SystemFileSystem.list(dir)
            .filter { SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
            .map { it.name }
    }

    /**
     * Deletes every file not in [referenced], except `draft-*` files modified less than a day ago
     * (a draft may belong to a recording that is still being saved). Returns the deleted names.
     */
    fun deleteOrphans(referenced: Set<String>): List<String> {
        val now = nowMs()
        val deleted = mutableListOf<String>()
        for (name in listAll()) {
            if (name in referenced) continue
            if (name.startsWith(DRAFT_PREFIX)) {
                val modified = lastModifiedMs(Path(baseDirectory, name).toString())
                if (modified != null && now - modified < STALE_DRAFT_MS) continue
            }
            delete(name)
            deleted += name
        }
        return deleted
    }

    private fun ensureDirectory() {
        val dir = Path(baseDirectory)
        if (!SystemFileSystem.exists(dir)) SystemFileSystem.createDirectories(dir)
    }

    companion object {
        const val DRAFT_PREFIX = "draft-"
        const val NOTE_PREFIX = "note-"
        const val STALE_DRAFT_MS: Long = 24L * 60 * 60 * 1000
    }
}

internal expect fun platformLastModifiedMs(path: String): Long?
