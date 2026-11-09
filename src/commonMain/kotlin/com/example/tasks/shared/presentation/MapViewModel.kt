package com.example.tasks.shared.presentation

import androidx.lifecycle.viewModelScope
import com.example.tasks.shared.data.DemoRoute
import com.example.tasks.shared.data.LoadState
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.TimeProvider
import com.example.tasks.shared.domain.ValidationError
import com.example.tasks.shared.domain.VoiceNote
import com.example.tasks.shared.geocoding.Geocoder
import com.example.tasks.shared.routing.Leg
import com.example.tasks.shared.routing.Route
import com.example.tasks.shared.routing.RoutingApi
import com.example.tasks.shared.routing.StraightLineRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

/** Today / Tomorrow / Yesterday get a word in the day selector; any other day shows its date. */
enum class DayLabel { TODAY, TOMORROW, YESTERDAY, NONE }

/**
 * One pin / route-list row. [order] is the 1-based position among the routed stops (those with a
 * time), null for a place without a time (pin without a number, no leg, listed after the routed ones).
 * [legFromPrevious] is the leg that ends at this stop (the first routed stop's leg starts at Start).
 * [legIsPast] = that leg ends at a past stop (draw it faded).
 */
data class MapStop(
    val task: Task,
    val place: Place,
    val order: Int?,
    val isPast: Boolean,
    val isOverdue: Boolean,
    val isDone: Boolean,
    val isNext: Boolean,
    val legFromPrevious: Leg?,
    val legIsPast: Boolean,
)

/**
 * Idle: nothing to show yet (also: stops without a time only, or the debounce window after a
 * change). Loading: the request is taking longer than 300 ms. Ready / Fallback carry the route
 * (Fallback = straight lines, no durations). Empty: the day has no task with a place.
 * Swift (SKIE): `switch onEnum(of: state.route)`.
 */
sealed interface RouteState {
    data object Idle : RouteState
    data object Loading : RouteState
    data class Ready(val route: Route) : RouteState
    data class Fallback(val route: Route) : RouteState
    data object Empty : RouteState
}

/** A pin being moved. The task keeps its time; [candidateAddress] is null while resolving or unresolved. */
data class MoveState(
    val taskId: Int,
    val originalPlace: Place,
    val candidate: GeoPoint,
    val candidateAddress: String?,
    val isResolvingAddress: Boolean,
)

/** Long-press draft. [address] is null while resolving ([isResolvingAddress]) or when nothing was found. */
data class NewTaskDraft(
    val point: GeoPoint,
    val address: String?,
    val isResolvingAddress: Boolean,
    /** The next full half hour on the viewed day (today) or 09:00 (other days). */
    val defaultTime: LocalTime,
)

data class MapUiState(
    // Day
    val day: LocalDate,
    val dayLabel: DayLabel,
    val start: Place,
    // Stops
    val stops: List<MapStop>,
    val isTasksLoading: Boolean,
    // Route + summary
    val route: RouteState,
    val stopsCount: Int,
    val routedStopsCount: Int,
    val totalDurationMinutes: Int?,
    val totalDistanceMeters: Double?,
    // Next stop / leave by
    val nextStop: MapStop?,
    val nextStopMinutesAway: Int?,
    val leaveBy: LocalTime?,
    val startIsPast: Boolean,
    // Interaction
    val selectedTaskId: Int?,
    val sheetExpanded: Boolean,
    val move: MoveState?,
    val newTaskDraft: NewTaskDraft?,
    // Network flags
    val tilesOffline: Boolean,
    val routeFailed: Boolean,
) {
    val selectedStop: MapStop? get() = selectedTaskId?.let { id -> stops.firstOrNull { it.task.id == id } }
}

/**
 * The whole state of the Map tab (MAP_HANDOFF §2.7). The apps render [uiState] and forward gestures.
 * Swift (SKIE): `for await state in viewModel.uiState { }`; call `viewModel.clear()` in deinit.
 *
 * Route rebuild: whenever the day or the routed stops (place / time / add / delete) change, debounced
 * 300 ms; a previous in-flight request is cancelled; [RouteState.Loading] appears only if the request
 * takes longer than 300 ms; a failed request becomes [RouteState.Fallback] (straight lines).
 * "Done" never changes the route. Results are cached in memory by point list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val repository: TaskRepository,
    private val routing: RoutingApi,
    private val geocoder: Geocoder,
    private val time: TimeProvider,
) : TasksViewModel() {

    private val validator = TaskValidator(DateProvider { time.now().date })

    private val day = MutableStateFlow(time.now().date)
    private val interaction = MutableStateFlow(Interaction())
    private val retryTick = MutableStateFlow(0)
    private val routeResult = MutableStateFlow(RouteResult(day.value, emptyList(), RouteState.Idle))
    private val routeCache = HashMap<List<GeoPoint>, Route>()

    private var geocodeJob: Job? = null
    private val draftVoiceFiles = HashSet<String>()

    private val clock = flow {
        while (true) {
            emit(time.now())
            delay(NOW_REFRESH_MS)
        }
    }

    val uiState: StateFlow<MapUiState> = combine(
        repository.state,
        day,
        routeResult,
        interaction,
        clock,
    ) { loadState, d, result, ui, now -> compose(loadState, d, result, ui, now) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = compose(repository.state.value, day.value, routeResult.value, interaction.value, time.now()),
        )

    init {
        // The route pipeline runs for the lifetime of the ViewModel, independent of subscribers.
        viewModelScope.launch {
            combine(day.flatMapLatest { d -> repository.tasksOn(d).map { d to it } }, retryTick) { (d, tasks), tick ->
                RouteRequest(d, routedPlaces(tasks).map { it.point }, tasks.any { it.place != null }, tick)
            }.distinctUntilChanged().collectLatest(::rebuildRoute)
        }
    }

    // ---------------------------------------------------------------- day

    fun previousDay() = selectDay(day.value.plus(-1, DateTimeUnit.DAY))

    fun nextDay() = selectDay(day.value.plus(1, DateTimeUnit.DAY))

    fun selectDay(date: LocalDate) {
        if (date == day.value) return
        discardDraft()
        geocodeJob?.cancel()
        interaction.update { it.copy(selectedTaskId = null, move = null, draft = null) }
        day.value = date
    }

    // ---------------------------------------------------------------- selection

    /** Selects a pin / row; the route sheet collapses. Ignored for tasks that are not a stop of the viewed day. */
    fun selectTask(id: Int) {
        val task = repository.task(id) ?: return
        if (task.place == null || task.dueDate != day.value) return
        interaction.update { it.copy(selectedTaskId = id, sheetExpanded = false) }
    }

    fun clearSelection() = interaction.update { it.copy(selectedTaskId = null) }

    fun setSheetExpanded(expanded: Boolean) = interaction.update { it.copy(sheetExpanded = expanded) }

    // ---------------------------------------------------------------- creating

    /** Drops a temporary pin at [point], opens the draft and reverse-geocodes it. No-op while moving a pin. */
    fun longPress(point: GeoPoint) {
        if (interaction.value.move != null) return
        discardDraft()
        geocodeJob?.cancel()
        interaction.update {
            it.copy(
                selectedTaskId = null,
                draft = NewTaskDraft(point, address = null, isResolvingAddress = true, defaultTime = defaultTimeFor(day.value, time.now())),
            )
        }
        geocodeJob = viewModelScope.launch {
            val address = resolveAddress(point)
            interaction.update { ui ->
                val draft = ui.draft
                if (draft == null || draft.point != point) ui else ui.copy(draft = draft.copy(address = address, isResolvingAddress = false))
            }
        }
    }

    fun cancelNewTask() {
        geocodeJob?.cancel()
        discardDraft()
        interaction.update { it.copy(draft = null) }
    }

    /** Title / notes errors for the new-task sheet (same validator as the editor; the date is never an error here). */
    fun validateNewTask(title: String, notes: String): List<ValidationError> =
        validator.validate(title, notes, day.value, originalDueDate = day.value)

    /**
     * Creates the task on the viewed day at the draft's place. Returns false (and keeps the draft)
     * when the draft is gone or the title / notes are invalid.
     */
    fun saveNewTask(title: String, notes: String, priority: Priority, time: LocalTime?, voiceNote: VoiceNote?): Boolean {
        val draft = interaction.value.draft ?: return false
        if (validateNewTask(title, notes).isNotEmpty()) return false
        geocodeJob?.cancel()
        val place = Place(draft.point, draft.address ?: coordinatesText(draft.point))
        repository.add(title, notes, priority, day.value, voiceNote, time, place)
        // Intermediate recordings that did not make it into the saved note.
        draftVoiceFiles.filter { it != voiceNote?.fileName }.forEach(repository::deleteVoiceFile)
        draftVoiceFiles.clear()
        interaction.update { it.copy(draft = null) }
        return true
    }

    /** Fed by `voiceVm.bindTo(mapViewModel)`: tracks recordings of the open draft so they can be deleted on cancel. */
    fun onDraftVoiceNoteChange(note: VoiceNote?) {
        if (note != null) draftVoiceFiles += note.fileName
    }

    // ---------------------------------------------------------------- moving

    /** Starts moving the pin of task [id] (it must have a place). The selection stays; the app hides the task sheet while [MapUiState.move] is set. */
    fun startMove(id: Int) {
        val place = repository.task(id)?.place ?: return
        discardDraft()
        geocodeJob?.cancel()
        interaction.update {
            it.copy(draft = null, move = MoveState(id, place, place.point, place.address, isResolvingAddress = false))
        }
    }

    /** The map panned under the fixed centre pin. Reverse geocoding is debounced 400 ms. */
    fun updateMoveCandidate(point: GeoPoint) {
        if (interaction.value.move == null) return
        interaction.update { ui ->
            ui.copy(move = ui.move?.copy(candidate = point, candidateAddress = null, isResolvingAddress = true))
        }
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            delay(MOVE_GEOCODE_DEBOUNCE_MS)
            val address = resolveAddress(point)
            interaction.update { ui ->
                val move = ui.move
                if (move == null || move.candidate != point) ui else ui.copy(move = move.copy(candidateAddress = address, isResolvingAddress = false))
            }
        }
    }

    /** Saves the new place (the time stays); the route is rebuilt by the pipeline. Waits for a pending address lookup. */
    fun confirmMove() {
        val pending = geocodeJob
        viewModelScope.launch {
            pending?.join()
            val move = interaction.value.move ?: return@launch
            val address = move.candidateAddress ?: if (move.candidate == move.originalPlace.point) move.originalPlace.address else coordinatesText(move.candidate)
            repository.setPlace(move.taskId, Place(move.candidate, address))
            interaction.update { it.copy(move = null) }
        }
    }

    fun cancelMove() {
        geocodeJob?.cancel()
        interaction.update { it.copy(move = null) }
    }

    // ---------------------------------------------------------------- task actions

    fun markDone(id: Int) {
        val task = repository.task(id) ?: return
        if (!task.isDone) repository.toggle(id)
    }

    fun delete(id: Int) {
        repository.delete(id)
        interaction.update { if (it.selectedTaskId == id) it.copy(selectedTaskId = null) else it }
    }

    // ---------------------------------------------------------------- network

    /** Rebuilds the route (also after a failure). The app reloads the map style itself and reports the outcome via [reportTilesOffline]. */
    fun retry() {
        retryTick.update { it + 1 }
    }

    fun reportTilesOffline(offline: Boolean) = interaction.update { it.copy(tilesOffline = offline) }

    override fun onCleared() {
        discardDraft()
    }

    // ---------------------------------------------------------------- route pipeline

    private suspend fun rebuildRoute(request: RouteRequest) {
        val points = listOf(DemoRoute.START.point) + request.routedPoints
        when {
            !request.hasPlaces -> routeResult.value = RouteResult(request.day, points, RouteState.Empty)
            request.routedPoints.isEmpty() -> routeResult.value = RouteResult(request.day, points, RouteState.Idle)
            else -> {
                routeCache[points]?.let {
                    routeResult.value = RouteResult(request.day, points, RouteState.Ready(it))
                    return
                }
                if (routeResult.value.day != request.day) routeResult.value = RouteResult(request.day, points, RouteState.Idle)
                delay(ROUTE_DEBOUNCE_MS)
                coroutineScope {
                    val loading = launch {
                        delay(LOADING_AFTER_MS)
                        routeResult.value = RouteResult(request.day, points, RouteState.Loading)
                    }
                    try {
                        val route = routing.route(points)
                        routeCache[points] = route
                        routeResult.value = RouteResult(request.day, points, RouteState.Ready(route))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        routeResult.value = RouteResult(request.day, points, RouteState.Fallback(StraightLineRouter.routeNow(points)))
                    } finally {
                        loading.cancel()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- state composition

    private fun compose(loadState: LoadState, d: LocalDate, result: RouteResult, ui: Interaction, now: LocalDateTime): MapUiState {
        val tasks = (loadState as? LoadState.Loaded)?.tasks.orEmpty().filter { it.dueDate == d && it.place != null }
        val routedTasks = tasks.filter { it.time != null }.sortedWith(compareBy({ it.time }, { it.id }))
        val unscheduled = tasks.filter { it.time == null }.sortedBy { it.id }

        val currentPoints = listOf(DemoRoute.START.point) + routedTasks.map { it.place!!.point }
        val route: RouteState = when {
            tasks.isEmpty() -> RouteState.Empty
            routedTasks.isEmpty() -> RouteState.Idle
            result.day != d -> RouteState.Idle
            // A stale Empty (the day just got its first place) must not outlive the debounce window.
            else -> result.state.takeUnless { it == RouteState.Empty } ?: RouteState.Idle
        }
        val routeObject = when (route) {
            is RouteState.Ready -> route.route
            is RouteState.Fallback -> route.route
            else -> null
        }
        // Legs are used only when the route was built for exactly these stops.
        val legs = if (routeObject != null && result.points == currentPoints) routeObject.legs else null

        val routedStops = routedTasks.mapIndexed { i, task ->
            val past = task.isPast(now)
            MapStop(
                task = task,
                place = task.place!!,
                order = i + 1,
                isPast = past,
                isOverdue = task.isOverdueAt(now),
                isDone = task.isDone,
                isNext = false,
                legFromPrevious = legs?.getOrNull(i),
                legIsPast = past && legs?.getOrNull(i) != null,
            )
        }
        val nextIndex = routedStops.indexOfFirst { !it.isDone && it.task.scheduledAt!! >= now }
        val stops = routedStops.mapIndexed { i, s -> if (i == nextIndex) s.copy(isNext = true) else s } + unscheduled.map { task ->
            MapStop(task, task.place!!, order = null, isPast = false, isOverdue = false, isDone = task.isDone, isNext = false, legFromPrevious = null, legIsPast = false)
        }
        val next = stops.firstOrNull { it.isNext }

        val firstDuration = legs?.firstOrNull()?.durationSeconds
        val firstTime = routedTasks.firstOrNull()?.scheduledAt
        val leaveBy = if (firstDuration != null && firstTime != null) leaveBy(firstTime, firstDuration) else null
        val startIsPast = when {
            leaveBy != null -> d.atTime(leaveBy) < now
            else -> routedStops.firstOrNull()?.isPast ?: false
        }

        val ready = route is RouteState.Ready
        val selected = ui.selectedTaskId?.takeIf { id -> stops.any { it.task.id == id } }
        return MapUiState(
            day = d,
            dayLabel = dayLabel(d, now.date),
            start = DemoRoute.START,
            stops = stops,
            isTasksLoading = loadState is LoadState.Loading,
            route = route,
            stopsCount = stops.size,
            routedStopsCount = routedStops.size,
            totalDurationMinutes = if (ready) routeObject!!.totalDurationSeconds?.let { ((it + 30) / 60).toInt() } else null,
            totalDistanceMeters = if (ready) routeObject!!.totalDistanceMeters else null,
            nextStop = next,
            nextStopMinutesAway = next?.let { minutesBetween(now, it.task.scheduledAt!!) },
            leaveBy = leaveBy,
            startIsPast = startIsPast,
            selectedTaskId = selected,
            sheetExpanded = ui.sheetExpanded,
            move = ui.move,
            newTaskDraft = ui.draft,
            tilesOffline = ui.tilesOffline,
            routeFailed = route is RouteState.Fallback,
        )
    }

    private suspend fun resolveAddress(point: GeoPoint): String? =
        try {
            geocoder.reverse(point)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun discardDraft() {
        draftVoiceFiles.forEach(repository::deleteVoiceFile)
        draftVoiceFiles.clear()
    }

    private data class Interaction(
        val selectedTaskId: Int? = null,
        val sheetExpanded: Boolean = false,
        val move: MoveState? = null,
        val draft: NewTaskDraft? = null,
        val tilesOffline: Boolean = false,
    )

    private data class RouteRequest(val day: LocalDate, val routedPoints: List<GeoPoint>, val hasPlaces: Boolean, val tick: Int)

    private data class RouteResult(val day: LocalDate, val points: List<GeoPoint>, val state: RouteState)

    companion object {
        const val ROUTE_DEBOUNCE_MS = 300L
        const val LOADING_AFTER_MS = 300L
        const val MOVE_GEOCODE_DEBOUNCE_MS = 400L
        private const val NOW_REFRESH_MS = 30_000L

        private fun routedPlaces(tasks: List<Task>): List<Place> =
            tasks.filter { it.place != null && it.time != null }
                .sortedWith(compareBy({ it.time }, { it.id }))
                .map { it.place!! }

        /** Next full half hour after now when viewing today, else 09:00. */
        internal fun defaultTimeFor(day: LocalDate, now: LocalDateTime): LocalTime {
            if (day != now.date) return LocalTime(9, 0)
            val next = (now.hour * 60 + now.minute) / 30 * 30 + 30
            return if (next >= 24 * 60) LocalTime(23, 30) else LocalTime(next / 60, next % 60)
        }

        /** First stop time minus the first leg, floored to the minute. */
        internal fun leaveBy(firstStop: LocalDateTime, legSeconds: Double): LocalTime {
            val seconds = firstStop.time.hour * 3600 + firstStop.time.minute * 60 - kotlin.math.ceil(legSeconds).toInt()
            val minutes = kotlin.math.floor(seconds / 60.0).toInt().coerceAtLeast(0)
            return LocalTime(minutes / 60, minutes % 60)
        }

        /** Whole minutes from [from] to [to], rounded up; never negative. */
        internal fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Int {
            val seconds = epochSeconds(to) - epochSeconds(from)
            return if (seconds <= 0) 0 else ((seconds + 59) / 60).toInt()
        }

        private fun epochSeconds(dt: LocalDateTime): Long = dt.date.toEpochDays() * 86_400L + dt.time.toSecondOfDay()

        internal fun dayLabel(day: LocalDate, today: LocalDate): DayLabel = when (day) {
            today -> DayLabel.TODAY
            today.plus(1, DateTimeUnit.DAY) -> DayLabel.TOMORROW
            today.plus(-1, DateTimeUnit.DAY) -> DayLabel.YESTERDAY
            else -> DayLabel.NONE
        }

        private fun coordinatesText(point: GeoPoint): String {
            fun f(v: Double) = ((v * 10_000).toLong() / 10_000.0).toString()
            return "${f(point.lat)}, ${f(point.lng)}"
        }
    }
}
