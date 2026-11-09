package com.example.tasks.shared.data

import com.example.tasks.shared.domain.DniproBounds
import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoRouteTest {
    private val fixtures = mutableListOf<RepoFixture>()

    @AfterTest
    fun tearDown() = fixtures.forEach { it.close() }

    private fun fixture(api: TaskApi) = RepoFixture(api).also { fixtures += it }

    @Test
    fun everyDemoPointAndTheStartAreInsideTheDniproBox() {
        assertEquals(10, DemoRoute.ADDRESSES.size)
        (DemoRoute.ADDRESSES + DemoRoute.START).forEach {
            assertTrue(DniproBounds.contains(it.point), "${it.address} ${it.point} is outside the Dnipro bbox")
        }
        assertEquals(DemoRoute.ADDRESSES.size, DemoRoute.ADDRESSES.map { it.point }.toSet().size, "duplicate demo points")
    }

    @Test
    fun spreadsTimesBetween0930And1800() {
        val times = DemoRoute.TODAY_TIMES + DemoRoute.TOMORROW_TIMES
        assertTrue(times.all { it >= LocalTime(9, 30) && it <= LocalTime(18, 0) })
        assertEquals(DemoRoute.TODAY_TIMES, DemoRoute.TODAY_TIMES.sorted())
    }

    @Test
    fun planUsesFiveForTodayAndFourForTomorrow() {
        val tasks = (1..6).map { task(it, dueDate = TODAY) } + (7..12).map { task(it, dueDate = day(1)) }
        val planned = DemoRoute.plan(tasks, TODAY)
        assertEquals(9, planned.size)
        assertEquals(5, planned.count { it.dueDate == TODAY })
        assertEquals(4, planned.count { it.dueDate == day(1) })
        assertTrue(planned.all { it.time != null && it.place != null })
    }

    @Test
    fun planSkipsDoneTasksAndTasksThatAlreadyHaveATimeOrPlace() {
        val busy = Place(GeoPoint(48.45, 35.05), "Mine")
        val tasks = listOf(
            task(1, dueDate = TODAY, isDone = true),
            task(2, dueDate = TODAY).copy(time = LocalTime(8, 0)),
            task(3, dueDate = TODAY).copy(place = busy),
            task(4, dueDate = TODAY),
        )
        val planned = DemoRoute.plan(tasks, TODAY)
        assertTrue(planned.none { it.id in setOf(1, 2, 3) })
        assertEquals(listOf(4), planned.filter { it.dueDate == TODAY }.map { it.id }.filter { it == 4 })
    }

    @Test
    fun planMovesFreeTasksToTodayAndTomorrowWhenThereAreNotEnough() {
        val tasks = (1..10).map { task(it, dueDate = day(-3 - it)) } // all old
        val planned = DemoRoute.plan(tasks, TODAY)
        assertEquals(9, planned.size)
        assertEquals(5, planned.count { it.dueDate == TODAY })
        assertEquals(4, planned.count { it.dueDate == day(1) })
        assertEquals(planned.size, planned.map { it.id }.toSet().size)
    }

    @Test
    fun planOrdersTimesByTaskId() {
        val planned = DemoRoute.plan((1..5).map { task(it, dueDate = TODAY) }, TODAY)
        assertEquals(DemoRoute.TODAY_TIMES, planned.sortedBy { it.id }.map { it.time })
    }

    @Test
    fun runsOnceOnFirstSeedAndNeverAgain() = runTest {
        val api = StaticApi((1..8).map { task(it, dueDate = TODAY) })
        val f = fixture(api)
        f.repository.load()
        val after = f.repository.tasks
        assertEquals(5, after.count { it.place != null && it.dueDate == TODAY })
        assertEquals(3, after.count { it.place == null })
        assertEquals("1", f.database.taskQueries.getMeta("demo_route_v1").executeAsOne())

        // The user removes a demo place; a later launch must not bring it back.
        val demo = after.first { it.place != null }
        f.repository.clearPlace(demo.id)
        f.repository.load()
        val again = DemoRoute.plan(f.repository.tasks, TODAY)
        assertTrue(again.isNotEmpty(), "the plan itself would still apply - only the Meta guard stops it")
        assertNull(f.repository.task(demo.id)?.place)
        assertEquals(4, f.repository.tasks.count { it.place != null })
    }

    @Test
    fun runsOnceOnAnExistingInstallAndNeverOverwrites() = runTest {
        val mine = Place(GeoPoint(48.45, 35.05), "My place")
        val f = fixture(StaticApi(emptyList())).seeded(
            task(1, dueDate = TODAY).copy(place = mine),
            task(2, dueDate = TODAY).copy(time = LocalTime(7, 15)),
            task(3, dueDate = TODAY),
            task(4, dueDate = day(1)),
        )
        f.repository.load()

        assertEquals(mine, f.repository.task(1)?.place)
        assertNull(f.repository.task(1)?.time)
        assertEquals(LocalTime(7, 15), f.repository.task(2)?.time)
        assertNull(f.repository.task(2)?.place)
        assertEquals(DemoRoute.TODAY_TIMES.first(), f.repository.task(3)?.time)
        assertEquals(DemoRoute.TOMORROW_TIMES.first(), f.repository.task(4)?.time)
        val snapshot = f.repository.tasks

        f.repository.load()
        assertEquals(snapshot, f.repository.tasks)
    }

    @Test
    fun noNetworkAndEmptyDatabaseDoesNotMarkTheDemoAsDone() = runTest {
        val failing = object : TaskApi {
            override suspend fun fetchTasks(today: kotlinx.datetime.LocalDate): List<Task> = error("offline")
        }
        val f = fixture(failing)
        f.repository.load()
        assertNull(f.database.taskQueries.getMeta("demo_route_v1").executeAsOneOrNull())
    }
}
