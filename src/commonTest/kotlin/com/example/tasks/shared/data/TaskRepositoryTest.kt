package com.example.tasks.shared.data

import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class FakeTaskApi(var result: () -> List<Task>) : TaskApi {
    var lastToday: LocalDate? = null
    override suspend fun fetchTasks(today: LocalDate): List<Task> {
        lastToday = today
        return result()
    }
}

class TaskRepositoryTest {

    private fun loadedRepo(vararg tasks: Task) = runTestRepo { tasks.toList() }

    private fun runTestRepo(result: () -> List<Task>): TaskRepository {
        val repo = TaskRepository(FakeTaskApi(result), fixedDateProvider)
        kotlinx.coroutines.test.runTest { repo.load() }
        return repo
    }

    @Test
    fun startsLoadingThenLoaded() = runTest {
        val api = FakeTaskApi { listOf(task(1)) }
        val repo = TaskRepository(api, fixedDateProvider)
        assertEquals(LoadState.Loading, repo.currentState)
        repo.load()
        assertEquals(LoadState.Loaded(listOf(task(1))), repo.currentState)
        assertEquals(TODAY, api.lastToday)
    }

    @Test
    fun failureGivesErrorAndRetryRecovers() = runTest {
        val api = FakeTaskApi { error("offline") }
        val repo = TaskRepository(api, fixedDateProvider)
        repo.load()
        assertEquals(LoadState.Error, repo.currentState)
        assertEquals(emptyList(), repo.tasks)

        api.result = { listOf(task(7)) }
        repo.load()
        assertIs<LoadState.Loaded>(repo.currentState)
        assertEquals(listOf(7), repo.tasks.map { it.id })
    }

    @Test
    fun addUsesMaxIdPlusOneAndTrimsTitle() {
        val repo = loadedRepo(task(3), task(10))
        val added = repo.add("  New task  ", "n", Priority.HIGH, day(1))
        assertEquals(11, added.id)
        assertEquals("New task", added.title)
        assertEquals(added, repo.task(11))
    }

    @Test
    fun addToEmptyListStartsAtOne() {
        val repo = loadedRepo()
        assertEquals(1, repo.add("First", "", Priority.LOW, TODAY).id)
    }

    @Test
    fun updateToggleDelete() {
        val repo = loadedRepo(task(1, title = "Old"), task(2))
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
}
