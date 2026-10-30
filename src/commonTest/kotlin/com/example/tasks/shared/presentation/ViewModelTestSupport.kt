package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.TaskApi
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.fixedDateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * viewModelScope runs on Dispatchers.Main — in unit tests we replace it with a test dispatcher
 * (works the same on Android host and iOS simulator tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {
    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDownMain() = Dispatchers.resetMain()

    /**
     * uiState uses stateIn(WhileSubscribed) — it only computes while someone collects it
     * (on Android: the Compose screen, on iOS: the Swift `for await` loop). In tests we subscribe
     * eagerly on an unconfined dispatcher so every change is reflected in `.value` immediately.
     */
    protected fun TestScope.subscribe(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    protected fun loadedRepository(vararg tasks: Task): TaskRepository {
        val repo = TaskRepository(StaticApi(tasks.toList()), fixedDateProvider)
        runTest { repo.load() }
        return repo
    }
}

internal class StaticApi(private val tasks: List<Task>) : TaskApi {
    override suspend fun fetchTasks(today: LocalDate): List<Task> = tasks
}
