package com.example.tasks.shared.data

import com.example.tasks.shared.domain.Logger
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.VoiceNote
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTaskApi(var result: () -> List<Task>) : TaskApi {
    var lastToday: LocalDate? = null
    var calls = 0
    var delayMs = 0L

    override suspend fun fetchTasks(today: LocalDate): List<Task> {
        calls++
        lastToday = today
        if (delayMs > 0) delay(delayMs)
        return result()
    }
}

private fun note(name: String) = VoiceNote(name, 5_000, List(40) { 0.5f })

class TaskRepositoryTest {

    private val fixtures = mutableListOf<RepoFixture>()

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
    }

    private fun fixture(api: TaskApi = FakeTaskApi { emptyList() }, logger: Logger = Logger.NONE) =
        RepoFixture(api, logger).also { fixtures += it }

    private fun seededRepo(vararg tasks: Task) = fixture().seeded(*tasks).repository

    @Test
    fun startsLoadingThenSeedsFromApi() = runTest {
        val api = FakeTaskApi { listOf(task(1)) }
        val repo = fixture(api).repository
        assertEquals(LoadState.Loading, repo.currentState)
        repo.load()
        assertEquals(LoadState.Loaded(listOf(task(1))), repo.currentState)
        assertEquals(TODAY, api.lastToday)
    }

    @Test
    fun seedsOnlyOnceAndNeverTouchesTheNetworkAfterwards() = runTest {
        val api = FakeTaskApi { listOf(task(1), task(2)) }
        val f = fixture(api)
        f.repository.load()
        f.repository.load()
        assertEquals(1, api.calls)

        // "App restart": a new repository over the same database.
        val restarted = TaskRepository(api, fixedDateProvider, f.database, f.files, f.scope)
        assertEquals(LoadState.Loaded(listOf(task(1), task(2))), restarted.currentState)
        restarted.load()
        assertEquals(1, api.calls)
    }

    @Test
    fun seededDatabaseWithNoTasksStaysLoadedAndEmpty() = runTest {
        val api = FakeTaskApi { listOf(task(1)) }
        val f = fixture(api)
        f.repository.load()
        f.repository.delete(1)
        assertEquals(LoadState.Loaded(emptyList()), f.repository.currentState)
        f.repository.load()
        assertEquals(1, api.calls) // deleted rows are not re-seeded
    }

    @Test
    fun failureOnEmptyDatabaseGivesErrorAndRetryRecovers() = runTest {
        val logged = mutableListOf<String>()
        val api = FakeTaskApi { error("offline") }
        val repo = fixture(api) { message, _ -> logged += message }.repository
        repo.load()
        assertEquals(LoadState.Error, repo.currentState)
        assertEquals(emptyList(), repo.tasks)
        assertEquals(1, logged.size)

        api.result = { listOf(task(7)) }
        repo.load()
        assertIs<LoadState.Loaded>(repo.currentState)
        assertEquals(listOf(7), repo.tasks.map { it.id })
    }

    @Test
    fun concurrentLoadsFetchOnce() = runTest {
        val api = FakeTaskApi { listOf(task(1)) }.also { it.delayMs = 100 }
        val repo = fixture(api).repository
        List(3) { async { repo.load() } }.awaitAll() // double (triple) Retry
        assertEquals(1, api.calls)
        assertEquals(listOf(1), repo.tasks.map { it.id })
    }

    @Test
    fun concurrentLoadsAfterFailureAlsoFetchOnce() = runTest {
        val api = FakeTaskApi { error("offline") }.also { it.delayMs = 100 }
        val repo = fixture(api).repository
        List(2) { async { repo.load() } }.awaitAll()
        assertEquals(1, api.calls)
        assertEquals(LoadState.Error, repo.currentState)
    }

    @Test
    fun errorWhileOfflineDoesNotHideTasksAddedLocally() = runTest {
        val api = FakeTaskApi { error("offline") }
        val repo = fixture(api).repository
        repo.load()
        repo.add("Local task", "", Priority.LOW, TODAY)
        assertEquals(listOf("Local task"), (repo.currentState as LoadState.Loaded).tasks.map { it.title })
    }

    @Test
    fun addUsesMaxIdPlusOneAndTrimsTitle() {
        val repo = seededRepo(task(3), task(10))
        val added = repo.add("  New task  ", "n", Priority.HIGH, day(1))
        assertEquals(11, added.id)
        assertEquals("New task", added.title)
        assertEquals(added, repo.task(11))
        assertEquals(added, repo.tasks.last())
    }

    @Test
    fun addAssignsIdsInsideTheTransaction() {
        val repo = seededRepo(task(5))
        val ids = List(5) { repo.add("Task $it", "", Priority.LOW, TODAY).id }
        assertEquals(listOf(6, 7, 8, 9, 10), ids)
        assertEquals(setOf(5, 6, 7, 8, 9, 10), repo.tasks.map { it.id }.toSet())
    }

    @Test
    fun addToEmptyListStartsAtOne() {
        val repo = seededRepo()
        assertEquals(1, repo.add("First", "", Priority.LOW, TODAY).id)
    }

    @Test
    fun updateToggleDelete() {
        val repo = seededRepo(task(1, title = "Old"), task(2))
        repo.update(task(1, title = "  Renamed  "))
        assertEquals("Renamed", repo.task(1)?.title)

        repo.toggle(2)
        assertEquals(true, repo.task(2)?.isDone)
        repo.toggle(2)
        assertEquals(false, repo.task(2)?.isDone)

        repo.delete(1)
        assertNull(repo.task(1))
        assertEquals(listOf(2), repo.tasks.map { it.id })
    }

    @Test
    fun updateDoesNotTouchTheVoiceNote() {
        val f = fixture().seeded(task(1))
        val repo = f.repository
        repo.setVoiceNote(1, note("note-a.m4a"))
        repo.update(task(1, title = "Changed")) // a stale Task without the note
        assertEquals("note-a.m4a", repo.task(1)?.voiceNote?.fileName)
    }

    @Test
    fun voiceNoteRoundTripsThroughTheDatabase() {
        val repo = seededRepo(task(1))
        val saved = VoiceNote("note-a.m4a", 42_000, List(40) { it / 39f }.map { (it * 1000).toInt() / 1000f })
        repo.setVoiceNote(1, saved)
        assertEquals(saved, repo.task(1)?.voiceNote)
        assertEquals("0:42", repo.tasks.single().voiceDurationText)
    }

    @Test
    fun setVoiceNoteDeletesTheReplacedFileButNotTheNewOne() {
        val f = fixture().seeded(task(1))
        writeFile(f.files.pathOf("note-a.m4a"))
        writeFile(f.files.pathOf("note-b.m4a"))
        f.repository.setVoiceNote(1, note("note-a.m4a"))
        f.repository.setVoiceNote(1, note("note-b.m4a"))
        assertFalse(f.files.exists("note-a.m4a"))
        assertTrue(f.files.exists("note-b.m4a"))
    }

    @Test
    fun clearVoiceNoteDeletesTheFile() {
        val f = fixture().seeded(task(1))
        writeFile(f.files.pathOf("note-a.m4a"))
        f.repository.setVoiceNote(1, note("note-a.m4a"))
        f.repository.clearVoiceNote(1)
        assertNull(f.repository.task(1)?.voiceNote)
        assertFalse(f.files.exists("note-a.m4a"))
    }

    @Test
    fun deleteRemovesTheTasksVoiceFile() {
        val f = fixture().seeded(task(1), task(2))
        writeFile(f.files.pathOf("note-a.m4a"))
        writeFile(f.files.pathOf("note-b.m4a"))
        f.repository.setVoiceNote(1, note("note-a.m4a"))
        f.repository.setVoiceNote(2, note("note-b.m4a"))
        f.repository.delete(1)
        assertFalse(f.files.exists("note-a.m4a"))
        assertTrue(f.files.exists("note-b.m4a"))
    }

    @Test
    fun startupCleanupRemovesUnreferencedFilesAndKeepsReferencedAndFreshDrafts() {
        val f = fixture()
        f.seeded(task(1, notes = "").copy(voiceNote = note("note-keep.m4a")))
        listOf("note-keep.m4a", "note-orphan.m4a", "draft-fresh.m4a").forEach { writeFile(f.files.pathOf(it)) }

        // "App start": constructing a repository runs the cleanup (unconfined scope: immediately).
        TaskRepository(FakeTaskApi { emptyList() }, fixedDateProvider, f.database, f.files, f.scope)

        assertTrue(f.files.exists("note-keep.m4a"))
        assertFalse(f.files.exists("note-orphan.m4a"))
        // A draft modified moments ago is kept (a recording may still be in flight).
        assertTrue(f.files.exists("draft-fresh.m4a"))
    }

    @Test
    fun staleDraftsAreCleanedUp() {
        val directory = newTempVoiceDirectory()
        try {
            val now = 10L * 24 * 60 * 60 * 1000
            val modified = mapOf("draft-old.m4a" to now - 2 * 24 * 60 * 60 * 1000, "draft-new.m4a" to now - 1000)
            val files = VoiceNoteFiles(directory, { path -> modified.entries.first { path.endsWith(it.key) }.value }, { now })
            listOf("draft-old.m4a", "draft-new.m4a", "note-x.m4a").forEach { writeFile(files.pathOf(it)) }

            val deleted = files.deleteOrphans(emptySet())

            assertEquals(setOf("draft-old.m4a", "note-x.m4a"), deleted.toSet())
            assertEquals(listOf("draft-new.m4a"), files.listAll())
        } finally {
            deleteDirectory(directory)
        }
    }

    @Test
    fun missingTaskIsIgnoredBySetVoiceNote() {
        val repo = seededRepo(task(1))
        repo.setVoiceNote(99, note("note-a.m4a"))
        assertNotNull(repo.task(1))
        assertNull(repo.task(99))
    }
}
