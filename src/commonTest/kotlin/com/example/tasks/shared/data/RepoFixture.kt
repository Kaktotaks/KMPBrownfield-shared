package com.example.tasks.shared.data

import com.example.tasks.shared.db.TasksDatabase
import com.example.tasks.shared.domain.Logger
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.fixedDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate

/**
 * Real repository on an in-memory database and a temp voice directory. The scope uses an
 * unconfined test dispatcher, so database emissions reach `state` synchronously and no test needs
 * a (nested) runTest just to set up data. Call [close] in tearDown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoFixture(
    private val api: TaskApi = StaticApi(emptyList()),
    private val logger: Logger = Logger.NONE,
) {
    val database: TasksDatabase = createInMemoryDatabase()
    val directory: String = newTempVoiceDirectory()
    val files = VoiceNoteFiles(directory)
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** Built eagerly: its startup orphan cleanup runs now, on the (still empty) directory. */
    val repository: TaskRepository = TaskRepository(api, fixedDateProvider, database, files, scope, logger)

    /** Pre-seeds [tasks] as an already-seeded database. */
    fun seeded(vararg tasks: Task): RepoFixture = apply { database.seed(tasks.toList()) }

    fun close() {
        scope.cancel()
        deleteDirectory(directory)
    }
}

class StaticApi(private val tasks: List<Task>) : TaskApi {
    var calls = 0
        private set

    override suspend fun fetchTasks(today: LocalDate): List<Task> {
        calls++
        return tasks
    }
}
