package com.example.tasks.shared.presentation

import com.example.tasks.shared.data.DemoRoute
import com.example.tasks.shared.domain.FixedTimeProvider
import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.NOV_4
import com.example.tasks.shared.domain.NOW
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.ValidationError
import com.example.tasks.shared.geocoding.Geocoder
import com.example.tasks.shared.routing.Leg
import com.example.tasks.shared.routing.Route
import com.example.tasks.shared.routing.RoutingApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val P = DemoRoute.ADDRESSES

/** Leg durations (s) and distances (m) of the design's sample day: 12, 9, 14, 11, 16 min. */
private val LEG_SECONDS = listOf(720.0, 540.0, 840.0, 660.0, 960.0)
private val LEG_METERS = listOf(3100.0, 2200.0, 3400.0, 2400.0, 3100.0)

private fun placed(id: Int, h: Int?, m: Int = 0, index: Int?, done: Boolean = false, priority: Priority = Priority.MEDIUM) =
    Task(
        id = id, title = "Task $id", notes = "", priority = priority, dueDate = NOV_4, isDone = done,
        voiceNote = null, time = h?.let { LocalTime(it, m) }, place = index?.let { P[it] },
    )

/** The design's day: 09:30 done, 11:00 overdue, 13:15, 15:30, 18:00, plus a pin without a time and a task without a place. */
private fun sampleDay() = arrayOf(
    placed(5, 18, 0, 4, priority = Priority.HIGH),
    placed(1, 9, 30, 0, done = true, priority = Priority.LOW),
    placed(3, 13, 15, 2),
    placed(2, 11, 0, 1, priority = Priority.HIGH),
    placed(4, 15, 30, 3, priority = Priority.LOW),
    placed(6, null, 0, 5),
    placed(7, 10, 0, null),
)

private class FakeRouting : RoutingApi {
    var calls = 0
    var failWith: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var cancelledCalls = 0
    val requested = mutableListOf<List<GeoPoint>>()

    override suspend fun route(points: List<GeoPoint>): Route {
        calls++
        requested += points
        try {
            gate?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelledCalls++
            throw e
        }
        failWith?.let { throw it }
        val legs = points.zipWithNext().mapIndexed { i, (a, b) ->
            Leg(LEG_SECONDS[i % 5], LEG_METERS[i % 5], listOf(a, b))
        }
        return Route(points, legs, legs.sumOf { it.durationSeconds!! }, legs.sumOf { it.distanceMeters })
    }
}

private class FakeGeocoder : Geocoder {
    var calls = 0
    var failing = false
    var address: String? = "Dropped St 1"
    val points = mutableListOf<GeoPoint>()

    override suspend fun reverse(point: GeoPoint): String? {
        calls++
        points += point
        if (failing) error("offline")
        return address
    }

    override suspend fun search(query: String, language: String): List<Place> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest : ViewModelTest() {

    private class Harness(val vm: MapViewModel, val routing: FakeRouting, val geocoder: FakeGeocoder)

    private fun TestScope.harness(vararg tasks: Task, routing: FakeRouting = FakeRouting()): Harness {
        val geocoder = FakeGeocoder()
        val repository = loadedRepository(*tasks)
        val vm = track(MapViewModel(repository, routing, geocoder, FixedTimeProvider(NOW)))
        subscribe(vm.uiState)
        return Harness(vm, routing, geocoder)
    }

    /** Lets the 300 ms debounce pass and a fast route request finish. */
    private fun TestScope.settle() {
        advanceTimeBy(MapViewModel.ROUTE_DEBOUNCE_MS + 1)
        runCurrent()
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun stopsAreOrderedByTimeAndUnscheduledPinsComeLast() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val stops = h.vm.uiState.value.stops
        assertEquals(listOf(1, 2, 3, 4, 5, 6), stops.map { it.task.id })
        assertEquals(listOf(1, 2, 3, 4, 5, null), stops.map { it.order })
        assertEquals(5, h.vm.uiState.value.routedStopsCount)
        assertEquals(6, h.vm.uiState.value.stopsCount) // the task without a place is not a stop
    }

    @Test
    fun unscheduledPinHasNoLegAndIsNotRouted() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val last = h.vm.uiState.value.stops.last()
        assertNull(last.order)
        assertNull(last.legFromPrevious)
        assertFalse(last.legIsPast)
        assertFalse(last.isPast)
        // Start + 5 routed places; the unscheduled pin (P[5]) is not part of the request.
        assertEquals(listOf(DemoRoute.START.point) + listOf(0, 1, 2, 3, 4).map { P[it].point }, h.routing.requested.single())
    }

    @Test
    fun tiesInTimeAreBrokenById() = vmTest {
        val h = harness(placed(2, 10, 0, 1), placed(1, 10, 0, 0))
        settle()
        assertEquals(listOf(1, 2), h.vm.uiState.value.stops.map { it.task.id })
    }

    // ------------------------------------------------------------------ past / overdue / legs

    @Test
    fun pastAndOverdueAgainstTheFrozenNow() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val s = h.vm.uiState.value.stops.associateBy { it.task.id }
        assertTrue(s.getValue(1).isPast && s.getValue(1).isDone && !s.getValue(1).isOverdue) // 09:30 done
        assertTrue(s.getValue(2).isPast && !s.getValue(2).isDone && s.getValue(2).isOverdue) // 11:00 overdue
        assertFalse(s.getValue(3).isPast) // 13:15
        assertFalse(s.getValue(4).isOverdue)
        assertFalse(s.getValue(5).isPast)
    }

    @Test
    fun legIsPastForLegsIntoPastStops() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val stops = h.vm.uiState.value.stops
        assertEquals(listOf(true, true, false, false, false, false), stops.map { it.legIsPast })
        assertEquals(listOf(720.0, 540.0, 840.0, 660.0, 960.0), stops.take(5).map { it.legFromPrevious?.durationSeconds })
    }

    // ------------------------------------------------------------------ next stop, leave by, summary

    @Test
    fun nextStopIsTheFirstNotDoneStopFromNow() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val state = h.vm.uiState.value
        assertEquals(3, state.nextStop?.task?.id)
        assertEquals(listOf(3), state.stops.filter { it.isNext }.map { it.task.id })
        assertEquals(35, state.nextStopMinutesAway) // 12:40 -> 13:15
        h.vm.markDone(3)
        assertEquals(4, h.vm.uiState.value.nextStop?.task?.id)
        assertEquals(170, h.vm.uiState.value.nextStopMinutesAway) // 12:40 -> 15:30
    }

    @Test
    fun nextStopIsNullWhenNothingIsLeftToday() = vmTest {
        val h = harness(placed(1, 9, 0, 0), placed(2, 10, 0, 1, done = true))
        settle()
        assertNull(h.vm.uiState.value.nextStop)
        assertNull(h.vm.uiState.value.nextStopMinutesAway)
    }

    @Test
    fun leaveByIsFirstStopTimeMinusFirstLeg() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val state = h.vm.uiState.value
        assertEquals(LocalTime(9, 18), state.leaveBy) // 09:30 - 12 min
        assertTrue(state.startIsPast) // 09:18 < 12:40 -> "Left 09:18"
    }

    @Test
    fun startIsNotPastWhenTheFirstStopIsAhead() = vmTest {
        val h = harness(placed(1, 13, 15, 0), placed(2, 15, 0, 1))
        settle()
        assertEquals(LocalTime(13, 3), h.vm.uiState.value.leaveBy)
        assertFalse(h.vm.uiState.value.startIsPast)
    }

    @Test
    fun summaryHasStopCountDurationAndDistance() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val state = h.vm.uiState.value
        assertIs<RouteState.Ready>(state.route)
        assertEquals(62, state.totalDurationMinutes) // 720+540+840+660+960 = 3720 s = 62 min -> "1 h 02 min"
        assertEquals(14_200.0, state.totalDistanceMeters)
        assertFalse(state.routeFailed)
    }

    @Test
    fun dayLabels() = vmTest {
        val h = harness(*sampleDay())
        assertEquals(DayLabel.TODAY, h.vm.uiState.value.dayLabel)
        h.vm.nextDay()
        assertEquals(DayLabel.TOMORROW, h.vm.uiState.value.dayLabel)
        h.vm.nextDay()
        assertEquals(DayLabel.NONE, h.vm.uiState.value.dayLabel)
        h.vm.selectDay(NOV_4.plus(-1, DateTimeUnit.DAY))
        assertEquals(DayLabel.YESTERDAY, h.vm.uiState.value.dayLabel)
        h.vm.previousDay()
        assertEquals(NOV_4.plus(-2, DateTimeUnit.DAY), h.vm.uiState.value.day)
    }

    // ------------------------------------------------------------------ route states

    @Test
    fun emptyDayHasEmptyRouteAndNoRequest() = vmTest {
        val h = harness(placed(1, 9, 0, null)) // a task without a place
        settle()
        assertEquals(RouteState.Empty, h.vm.uiState.value.route)
        assertEquals(0, h.routing.calls)
        assertEquals(emptyList(), h.vm.uiState.value.stops)
    }

    @Test
    fun onlyUnscheduledPinsMeansNothingToRoute() = vmTest {
        val h = harness(placed(1, null, 0, 0))
        settle()
        assertEquals(RouteState.Idle, h.vm.uiState.value.route)
        assertEquals(0, h.routing.calls)
        assertEquals(1, h.vm.uiState.value.stopsCount)
    }

    @Test
    fun failedRoutingFallsBackToStraightLines() = vmTest {
        val routing = FakeRouting().apply { failWith = RuntimeException("down") }
        val h = harness(*sampleDay(), routing = routing)
        settle()
        val state = h.vm.uiState.value
        val fallback = assertIs<RouteState.Fallback>(state.route)
        assertTrue(fallback.route.isFallback)
        assertNull(fallback.route.totalDurationSeconds)
        assertEquals(5, fallback.route.legs.size)
        assertTrue(fallback.route.legs.all { it.durationSeconds == null && it.geometry.size == 2 })
        assertTrue(state.routeFailed)
        assertNull(state.totalDurationMinutes)
        assertNull(state.totalDistanceMeters)
        assertNull(state.leaveBy)
        // Past legs are still flagged, so they can be faded.
        assertEquals(listOf(true, true, false, false, false), state.stops.take(5).map { it.legIsPast })
    }

    @Test
    fun retryAfterAFailureRebuildsTheRoute() = vmTest {
        val routing = FakeRouting().apply { failWith = RuntimeException("down") }
        val h = harness(*sampleDay(), routing = routing)
        settle()
        assertIs<RouteState.Fallback>(h.vm.uiState.value.route)
        routing.failWith = null
        h.vm.retry()
        settle()
        assertIs<RouteState.Ready>(h.vm.uiState.value.route)
        assertEquals(2, routing.calls)
    }

    @Test
    fun loadingShowsOnlyWhenTheRequestIsSlow() = vmTest {
        val routing = FakeRouting().apply { gate = CompletableDeferred() }
        val h = harness(*sampleDay(), routing = routing)
        runCurrent()
        assertEquals(RouteState.Idle, h.vm.uiState.value.route)
        advanceTimeBy(MapViewModel.ROUTE_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, routing.calls)
        assertEquals(RouteState.Idle, h.vm.uiState.value.route) // started, but not slow yet
        advanceTimeBy(MapViewModel.LOADING_AFTER_MS)
        runCurrent()
        assertEquals(RouteState.Loading, h.vm.uiState.value.route)
        routing.gate!!.complete(Unit)
        runCurrent()
        assertIs<RouteState.Ready>(h.vm.uiState.value.route)
    }

    @Test
    fun changingTheDayCancelsTheInFlightRoute() = vmTest {
        val routing = FakeRouting().apply { gate = CompletableDeferred() }
        val h = harness(*sampleDay(), routing = routing)
        advanceTimeBy(MapViewModel.ROUTE_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, routing.calls)
        assertEquals(0, routing.cancelledCalls)
        h.vm.nextDay() // tomorrow is empty
        runCurrent()
        assertEquals(1, routing.cancelledCalls)
        assertEquals(RouteState.Empty, h.vm.uiState.value.route)
        // The late answer of the cancelled request must not leak into the new day.
        routing.gate!!.complete(Unit)
        runCurrent()
        assertEquals(RouteState.Empty, h.vm.uiState.value.route)
    }

    @Test
    fun aChangeInsideTheDebounceWindowIssuesOneRequest() = vmTest {
        val h = harness(placed(1, 9, 0, 0), placed(2, 10, 0, 1))
        advanceTimeBy(100)
        h.vm.delete(2)
        h.vm.setSheetExpanded(true) // unrelated interaction does not restart anything
        settle()
        assertEquals(1, h.routing.calls)
        assertEquals(2, h.routing.requested.single().size)
    }

    @Test
    fun markingDoneDoesNotRebuildTheRoute() = vmTest {
        val h = harness(*sampleDay())
        settle()
        h.vm.markDone(3)
        settle()
        assertEquals(1, h.routing.calls)
    }

    @Test
    fun resultsAreCachedByPointList() = vmTest {
        val h = harness(*sampleDay())
        settle()
        h.vm.nextDay()
        runCurrent()
        h.vm.previousDay()
        runCurrent()
        assertEquals(1, h.routing.calls)
        assertIs<RouteState.Ready>(h.vm.uiState.value.route)
    }

    // ------------------------------------------------------------------ selection

    @Test
    fun selectingAStopCollapsesTheSheet() = vmTest {
        val h = harness(*sampleDay())
        settle()
        h.vm.setSheetExpanded(true)
        assertTrue(h.vm.uiState.value.sheetExpanded)
        h.vm.selectTask(3)
        assertEquals(3, h.vm.uiState.value.selectedTaskId)
        assertFalse(h.vm.uiState.value.sheetExpanded)
        assertEquals(3, h.vm.uiState.value.selectedStop?.task?.id)
        h.vm.clearSelection()
        assertNull(h.vm.uiState.value.selectedTaskId)
    }

    @Test
    fun selectingATaskWithoutAPlaceIsIgnored() = vmTest {
        val h = harness(*sampleDay())
        h.vm.selectTask(7)
        assertNull(h.vm.uiState.value.selectedTaskId)
    }

    @Test
    fun deletingTheSelectedStopClearsTheSelection() = vmTest {
        val h = harness(*sampleDay())
        settle()
        h.vm.selectTask(3)
        h.vm.delete(3)
        settle()
        assertNull(h.vm.uiState.value.selectedTaskId)
        assertEquals(listOf(1, 2, 4, 5, 6), h.vm.uiState.value.stops.map { it.task.id })
        assertEquals(2, h.routing.calls) // the stop list changed: rebuilt
    }

    @Test
    fun changingTheDayClearsSelectionAndMove() = vmTest {
        val h = harness(*sampleDay())
        h.vm.selectTask(3)
        h.vm.startMove(3)
        h.vm.nextDay()
        assertNull(h.vm.uiState.value.selectedTaskId)
        assertNull(h.vm.uiState.value.move)
    }

    // ------------------------------------------------------------------ creating

    @Test
    fun longPressOpensADraftAndResolvesTheAddress() = vmTest {
        val h = harness(*sampleDay())
        val point = GeoPoint(48.46, 35.05)
        h.vm.longPress(point)
        val draft = assertNotNull(h.vm.uiState.value.newTaskDraft)
        assertEquals(point, draft.point)
        assertEquals("Dropped St 1", draft.address)
        assertFalse(draft.isResolvingAddress)
        assertEquals(LocalTime(13, 0), draft.defaultTime) // next full half hour after 12:40
    }

    @Test
    fun draftAddressStaysNullWhenGeocodingFails() = vmTest {
        val h = harness(*sampleDay())
        h.geocoder.failing = true
        h.vm.longPress(GeoPoint(48.46, 35.05))
        val draft = assertNotNull(h.vm.uiState.value.newTaskDraft)
        assertNull(draft.address)
        assertFalse(draft.isResolvingAddress)
    }

    @Test
    fun defaultTimeRounding() {
        val d = NOV_4
        assertEquals(LocalTime(13, 0), MapViewModel.defaultTimeFor(d, NOW))
        assertEquals(LocalTime(13, 0), MapViewModel.defaultTimeFor(d, kotlinx.datetime.LocalDateTime(2026, 11, 4, 12, 30)))
        assertEquals(LocalTime(12, 30), MapViewModel.defaultTimeFor(d, kotlinx.datetime.LocalDateTime(2026, 11, 4, 12, 15)))
        assertEquals(LocalTime(23, 30), MapViewModel.defaultTimeFor(d, kotlinx.datetime.LocalDateTime(2026, 11, 4, 23, 50)))
        assertEquals(LocalTime(9, 0), MapViewModel.defaultTimeFor(d.plus(1, DateTimeUnit.DAY), NOW))
    }

    @Test
    fun saveNewTaskCreatesATaskOnTheViewedDayAtTheDraftPlace() = vmTest {
        val h = harness(*sampleDay())
        settle()
        val point = GeoPoint(48.46, 35.05)
        h.vm.longPress(point)
        assertTrue(h.vm.saveNewTask("Buy milk", "2 l", Priority.HIGH, LocalTime(16, 0), null))
        assertNull(h.vm.uiState.value.newTaskDraft)
        settle()
        val created = h.vm.uiState.value.stops.single { it.task.title == "Buy milk" }
        assertEquals(NOV_4, created.task.dueDate)
        assertEquals(LocalTime(16, 0), created.task.time)
        assertEquals(Place(point, "Dropped St 1"), created.task.place)
        assertEquals(Priority.HIGH, created.task.priority)
        assertEquals(5, created.order) // 16:00 comes after 15:30 and before 18:00
        assertEquals(6, h.vm.uiState.value.routedStopsCount)
        assertEquals(2, h.routing.calls)
    }

    @Test
    fun saveNewTaskFallsBackToCoordinatesWhenTheAddressIsUnknown() = vmTest {
        val h = harness(*sampleDay())
        h.geocoder.failing = true
        h.vm.longPress(GeoPoint(48.4601234, 35.0512345))
        assertTrue(h.vm.saveNewTask("Buy milk", "", Priority.LOW, null, null))
        settle()
        val created = h.vm.uiState.value.stops.single { it.task.title == "Buy milk" }
        assertEquals("48.4601, 35.0512", created.task.place?.address)
        assertNull(created.order) // no time -> pin without a number
    }

    @Test
    fun invalidTitleIsNotSavedAndTheDraftStays() = vmTest {
        val h = harness(*sampleDay())
        h.vm.longPress(GeoPoint(48.46, 35.05))
        assertEquals(listOf(ValidationError.TITLE_TOO_SHORT), h.vm.validateNewTask("ab", ""))
        assertFalse(h.vm.saveNewTask("ab", "", Priority.LOW, null, null))
        assertNotNull(h.vm.uiState.value.newTaskDraft)
        assertTrue(h.vm.validateNewTask("abc", "").isEmpty())
    }

    @Test
    fun savingOnAPastDayIsAllowed() = vmTest {
        val h = harness(*sampleDay())
        h.vm.previousDay()
        h.vm.longPress(GeoPoint(48.46, 35.05))
        assertTrue(h.vm.saveNewTask("Yesterday thing", "", Priority.LOW, null, null))
    }

    @Test
    fun cancelNewTaskDropsTheDraft() = vmTest {
        val h = harness(*sampleDay())
        h.vm.longPress(GeoPoint(48.46, 35.05))
        h.vm.cancelNewTask()
        assertNull(h.vm.uiState.value.newTaskDraft)
        assertFalse(h.vm.saveNewTask("Buy milk", "", Priority.LOW, null, null))
    }

    // ------------------------------------------------------------------ moving

    @Test
    fun startMoveKeepsTheOriginalPlaceAndTime() = vmTest {
        val h = harness(*sampleDay())
        h.vm.startMove(3)
        val move = assertNotNull(h.vm.uiState.value.move)
        assertEquals(3, move.taskId)
        assertEquals(P[2], move.originalPlace)
        assertEquals(P[2].point, move.candidate)
        assertEquals(P[2].address, move.candidateAddress)
    }

    @Test
    fun moveGeocodingIsDebouncedBy400ms() = vmTest {
        val h = harness(*sampleDay())
        h.vm.startMove(3)
        h.vm.updateMoveCandidate(GeoPoint(48.46, 35.05))
        advanceTimeBy(200)
        h.vm.updateMoveCandidate(GeoPoint(48.461, 35.051))
        advanceTimeBy(399)
        runCurrent()
        assertEquals(0, h.geocoder.calls)
        assertTrue(h.vm.uiState.value.move!!.isResolvingAddress)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, h.geocoder.calls)
        assertEquals(GeoPoint(48.461, 35.051), h.geocoder.points.single())
        assertEquals("Dropped St 1", h.vm.uiState.value.move!!.candidateAddress)
        assertFalse(h.vm.uiState.value.move!!.isResolvingAddress)
    }

    @Test
    fun confirmMoveSavesThePlaceKeepsTheTimeAndRebuildsTheRoute() = vmTest {
        val h = harness(*sampleDay())
        settle()
        h.vm.startMove(3)
        val target = GeoPoint(48.4601, 35.0502)
        h.vm.updateMoveCandidate(target)
        advanceTimeBy(500)
        runCurrent()
        h.vm.confirmMove()
        runCurrent()
        assertNull(h.vm.uiState.value.move)
        settle()
        val moved = h.vm.uiState.value.stops.single { it.task.id == 3 }
        assertEquals(Place(target, "Dropped St 1"), moved.task.place)
        assertEquals(LocalTime(13, 15), moved.task.time)
        assertEquals(3, moved.order)
        assertEquals(2, h.routing.calls)
        assertEquals(target, h.routing.requested.last()[3])
    }

    @Test
    fun confirmMoveWaitsForAPendingAddressLookup() = vmTest {
        val h = harness(*sampleDay())
        h.vm.startMove(3)
        h.vm.updateMoveCandidate(GeoPoint(48.4601, 35.0502))
        h.vm.confirmMove() // before the 400 ms debounce elapsed
        advanceTimeBy(401)
        runCurrent()
        assertNull(h.vm.uiState.value.move)
        assertEquals("Dropped St 1", h.vm.uiState.value.stops.single { it.task.id == 3 }.place.address)
    }

    @Test
    fun cancelMoveLeavesTheTaskUntouched() = vmTest {
        val h = harness(*sampleDay())
        h.vm.startMove(3)
        h.vm.updateMoveCandidate(GeoPoint(48.4601, 35.0502))
        h.vm.cancelMove()
        advanceTimeBy(1000)
        runCurrent()
        assertNull(h.vm.uiState.value.move)
        assertEquals(P[2], h.vm.uiState.value.stops.single { it.task.id == 3 }.place)
        assertEquals(0, h.geocoder.calls)
    }

    // ------------------------------------------------------------------ misc actions

    @Test
    fun markDoneOnlyMarksAndIsIdempotent() = vmTest {
        val h = harness(*sampleDay())
        h.vm.markDone(3)
        h.vm.markDone(3)
        assertTrue(h.vm.uiState.value.stops.single { it.task.id == 3 }.isDone)
    }

    @Test
    fun networkFlags() = vmTest {
        val h = harness(*sampleDay())
        assertFalse(h.vm.uiState.value.tilesOffline)
        h.vm.reportTilesOffline(true)
        assertTrue(h.vm.uiState.value.tilesOffline)
        h.vm.reportTilesOffline(false)
        assertFalse(h.vm.uiState.value.tilesOffline)
    }

    @Test
    fun startIsTheFixedDobrovoltsivLane() = vmTest {
        val h = harness(*sampleDay())
        assertEquals(DemoRoute.START, h.vm.uiState.value.start)
        assertEquals("Dobrovoltsiv Ln 6", h.vm.uiState.value.start.address)
    }
}
