package com.example.tasks.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.tasks.shared.db.TaskEntity
import com.example.tasks.shared.db.TasksDatabase
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Logger
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.VoiceNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.coroutines.ContinuationInterceptor

// In plain ObjC export a sealed interface is three unrelated classes (LoadStateLoading /
// LoadStateError / LoadStateLoaded) with no exhaustive switch. SKIE adds `onEnum(of: state)`,
// a Swift enum with .loading / .error / .loaded(LoadStateLoaded), so the switch is exhaustive.
sealed interface LoadState {
    data object Loading : LoadState
    data object Error : LoadState
    data class Loaded(val tasks: List<Task>) : LoadState
}

/**
 * Offline-first source of truth for tasks. The SQLite database ([TasksDatabase]) is the truth;
 * [state] is the database observed as a hot StateFlow.
 *
 * - First launch: [load] fetches from the API and seeds the database (rows + `seeded=1`) in one
 *   transaction. Afterwards [load] never touches the network. No network and an empty database →
 *   [LoadState.Error].
 * - Writes ([add], [update], [toggle], [delete], [setVoiceNote], [clearVoiceNote]) are plain
 *   non-suspend calls: each is one small SQLite transaction executed before the call returns
 *   (so [add] can return the new task and callers can rely on ordering). The [state] flow then
 *   re-emits asynchronously on [scope]'s dispatcher.
 * - Voice-note files are owned here: deleting a task, clearing or replacing its note deletes the
 *   file; on construction, files no row references are removed in the background.
 *
 * [scope] must outlive the repository (app scope); it also supplies the dispatcher for database reads.
 */
class TaskRepository(
    private val api: TaskApi,
    private val dateProvider: DateProvider,
    private val database: TasksDatabase,
    /** The voice-note files of this repository; pass the same instance to VoiceNoteViewModel. */
    val voiceFiles: VoiceNoteFiles,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    // Swift does not see default arguments: an explicit overload instead of `logger = Logger.NONE`.
    constructor(
        api: TaskApi,
        dateProvider: DateProvider,
        database: TasksDatabase,
        voiceFiles: VoiceNoteFiles,
        scope: CoroutineScope,
    ) : this(api, dateProvider, database, voiceFiles, scope, Logger.NONE)

    private val queries = database.taskQueries
    private val ioContext = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default

    private val loadMutex = Mutex()
    private val loadGeneration = MutableStateFlow(0)
    private val loadFailed = MutableStateFlow(false)

    /**
     * Kotlin/Android: `state.collect { }`.
     * Swift (via SKIE): `for await state in repository.state { }`, `repository.state.value`.
     *
     * Loaded as soon as the database is seeded or holds any row; Error only when a load failed and
     * the database is still empty; Loading otherwise (also before the first database emission).
     */
    val state: StateFlow<LoadState> = combine(
        queries.selectAll().asFlow().mapToList(ioContext).map { rows -> rows.map { it.toTask() } },
        queries.getMeta(SEEDED_KEY).asFlow().mapToOneOrNull(ioContext),
        loadFailed,
    ) { tasks, seeded, failed ->
        when {
            seeded == SEEDED_VALUE || tasks.isNotEmpty() -> LoadState.Loaded(tasks)
            failed -> LoadState.Error
            else -> LoadState.Loading
        }
    }.stateIn(scope, SharingStarted.Eagerly, LoadState.Loading)

    /** Current state snapshot (same as state.value). */
    val currentState: LoadState get() = state.value

    /** Tasks of the current state (empty while loading or on error). */
    val tasks: List<Task> get() = (state.value as? LoadState.Loaded)?.tasks.orEmpty()

    init {
        scope.launch { withContext(ioContext) { deleteOrphanedVoiceFiles() } }
    }

    /**
     * Makes sure the database is seeded.
     * Swift (via SKIE): `try await repository.load()` from any thread.
     *
     * Serialized by a Mutex: when several callers race (e.g. a double Retry tap), the first does the
     * work and the others return without a second network call. Already seeded → no network.
     * Failure is reported through [state] (Error), not thrown.
     */
    suspend fun load() {
        val seen = loadGeneration.value
        loadMutex.withLock {
            if (loadGeneration.value != seen) return // a load finished while we were waiting
            loadLocked()
            loadGeneration.update { it + 1 }
        }
    }

    private suspend fun loadLocked() {
        loadFailed.value = false // Retry shows Loading again
        val seeded = withContext(ioContext) { queries.getMeta(SEEDED_KEY).executeAsOneOrNull() == SEEDED_VALUE }
        if (seeded) return
        try {
            val fetched = api.fetchTasks(dateProvider.today())
            withContext(ioContext) {
                database.transaction {
                    // OR IGNORE: ids the user already took while offline-and-empty keep the local row.
                    fetched.forEach { queries.insertOrIgnore(it) }
                    queries.setMeta(SEEDED_KEY, SEEDED_VALUE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("TaskRepository: load failed", e)
            loadFailed.value = true
        }
    }

    /** Reads straight from the database, so it is accurate right after a write. */
    fun task(id: Int): Task? = queries.selectById(id.toLong()).executeAsOneOrNull()?.toTask()

    /** Adds a task with id = max id + 1 (computed inside the transaction) and returns it. */
    fun add(title: String, notes: String, priority: Priority, dueDate: LocalDate): Task =
        add(title, notes, priority, dueDate, null)

    /** Same as above, with a voice note stored in the same transaction. */
    fun add(title: String, notes: String, priority: Priority, dueDate: LocalDate, voiceNote: VoiceNote?): Task =
        database.transactionWithResult {
            val task = Task(
                id = queries.maxId().executeAsOne().toInt() + 1,
                title = title.trim(),
                notes = notes,
                priority = priority,
                dueDate = dueDate,
                isDone = false,
                voiceNote = voiceNote,
            )
            queries.insert(task)
            task
        }

    /** Updates title, notes, priority, due date and done state. The voice note is NOT touched: use [setVoiceNote] / [clearVoiceNote]. */
    fun update(task: Task) {
        queries.update(
            title = task.title.trim(),
            notes = task.notes,
            priority = task.priority.name,
            due_date = task.dueDate.toString(),
            is_done = if (task.isDone) 1L else 0L,
            id = task.id.toLong(),
        )
    }

    fun toggle(id: Int) {
        database.transaction {
            val row = queries.selectById(id.toLong()).executeAsOneOrNull() ?: return@transaction
            queries.setDone(is_done = if (row.is_done == 0L) 1L else 0L, id = id.toLong())
        }
    }

    /** Deletes the task and its voice-note file. */
    fun delete(id: Int) {
        val file = database.transactionWithResult {
            val file = queries.voiceFileOf(id.toLong()).executeAsList().firstOrNull()?.voice_file
            queries.delete(id.toLong())
            file
        }
        file?.let(voiceFiles::delete)
    }

    /** Attaches [note] to the task; a previous note's file (if different) is deleted. No-op if the task does not exist. */
    fun setVoiceNote(taskId: Int, note: VoiceNote) {
        val previous = database.transactionWithResult {
            if (queries.selectById(taskId.toLong()).executeAsOneOrNull() == null) return@transactionWithResult null
            val previous = queries.voiceFileOf(taskId.toLong()).executeAsList().firstOrNull()?.voice_file
            queries.setVoiceNote(note.fileName, note.durationMs, encodeWaveform(note.waveform), taskId.toLong())
            previous
        }
        if (previous != null && previous != note.fileName) voiceFiles.delete(previous)
    }

    /** Removes the task's voice note and deletes its file. */
    fun clearVoiceNote(taskId: Int) {
        val previous = database.transactionWithResult {
            val previous = queries.voiceFileOf(taskId.toLong()).executeAsList().firstOrNull()?.voice_file
            queries.clearVoiceNote(taskId.toLong())
            previous
        }
        previous?.let(voiceFiles::delete)
    }

    internal fun deleteVoiceFile(name: String) = voiceFiles.delete(name)

    private fun deleteOrphanedVoiceFiles() {
        try {
            val referenced = queries.allVoiceFiles().executeAsList().filterNotNull().toSet()
            val deleted = voiceFiles.deleteOrphans(referenced)
            if (deleted.isNotEmpty()) logger.log("TaskRepository: removed ${deleted.size} orphaned voice file(s)", null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("TaskRepository: orphan cleanup failed", e)
        }
    }

    private companion object {
        const val SEEDED_KEY = "seeded"
        const val SEEDED_VALUE = "1"
    }
}

private fun com.example.tasks.shared.db.TaskQueries.insertOrIgnore(task: Task) = insertOrIgnore(
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

private fun com.example.tasks.shared.db.TaskQueries.insert(task: Task) = insert(
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

private fun TaskEntity.toTask() = Task(
    id = id.toInt(),
    title = title,
    notes = notes,
    priority = Priority.entries.firstOrNull { it.name == priority } ?: Priority.MEDIUM,
    dueDate = LocalDate.parse(due_date),
    isDone = is_done != 0L,
    voiceNote = voice_file?.let { VoiceNote(it, voice_duration_ms ?: 0L, decodeWaveform(voice_waveform)) },
)

/** Waveform column format: comma-separated floats, 3 decimals ("0.080,0.512,..."). */
internal fun encodeWaveform(waveform: List<Float>): String = waveform.joinToString(",") { value ->
    val scaled = (value.coerceIn(0f, 1f) * 1000).toInt()
    "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
}

internal fun decodeWaveform(text: String?): List<Float> =
    text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
