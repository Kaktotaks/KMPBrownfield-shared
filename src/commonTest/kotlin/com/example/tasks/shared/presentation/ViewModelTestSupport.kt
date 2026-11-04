package com.example.tasks.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.example.tasks.shared.data.RepoFixture
import com.example.tasks.shared.data.StaticApi
import com.example.tasks.shared.data.TaskApi
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * viewModelScope runs on Dispatchers.Main — in unit tests we replace it with a test dispatcher
 * (works the same on Android host and iOS simulator tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTest {
    protected val fixtures = mutableListOf<RepoFixture>()
    private val viewModels = mutableListOf<ViewModel>()

    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private fun clearViewModels() {
        // Clearing runs onCleared and cancels viewModelScope (no leaked tick loops between tests).
        viewModels.forEach { vm -> ViewModelStore().also { it.put("vm", vm) }.clear() }
        viewModels.clear()
    }

    /**
     * runTest that clears tracked ViewModels before it returns: runTest drains the shared scheduler
     * at the end, and a still-running 50 ms tick loop (recording / playback) would never go idle.
     */
    protected fun vmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            clearViewModels()
        }
    }

    @AfterTest
    fun tearDownMain() {
        clearViewModels()
        fixtures.forEach { it.close() }
        fixtures.clear()
        Dispatchers.resetMain()
    }

    /** Registers [vm] to be cleared after the test. */
    protected fun <T : ViewModel> track(vm: T): T = vm.also { viewModels += it }

    /** A fixture whose database is already seeded with [tasks]. */
    protected fun fixtureWith(vararg tasks: Task, api: TaskApi = StaticApi(emptyList())): RepoFixture =
        RepoFixture(api).seeded(*tasks).also { fixtures += it }

    /**
     * uiState uses stateIn(WhileSubscribed) — it only computes while someone collects it
     * (on Android: the Compose screen, on iOS: the Swift `for await` loop). In tests we subscribe
     * eagerly on an unconfined dispatcher so every change is reflected in `.value` immediately.
     */
    protected fun TestScope.subscribe(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    /**
     * A repository over a seeded in-memory database: no network, and no runTest of its own
     * (the old helper started a nested runTest inside the caller's runTest).
     */
    protected fun loadedRepository(vararg tasks: Task): TaskRepository = fixtureWith(*tasks).repository
}
