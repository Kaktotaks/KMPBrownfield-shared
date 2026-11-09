package com.example.tasks.shared.presentation

import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.TaskValidator
import com.example.tasks.shared.domain.fixedDateProvider
import com.example.tasks.shared.domain.task
import com.example.tasks.shared.geocoding.Geocoder
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SHEVCHENKA = Place(GeoPoint(48.4560234, 35.0571877), "Shevchenka St 9")

class EditorTimePlaceTest : ViewModelTest() {
    private val validator = TaskValidator(fixedDateProvider)

    @Test
    fun newTaskStartsWithoutTimeAndPlaceAndSavesBoth() {
        val repo = loadedRepository()
        val vm = TaskEditorViewModel(TaskEditorViewModel.NEW_TASK_ID, repo, validator, fixedDateProvider)
        assertNull(vm.uiState.value.time)
        assertNull(vm.uiState.value.place)
        vm.onTitleChange("Dentist")
        vm.onTimeChange(LocalTime(13, 15))
        vm.onPlaceChange(SHEVCHENKA)
        assertTrue(vm.save())
        val saved = repo.tasks.single()
        assertEquals(LocalTime(13, 15), saved.time)
        assertEquals(SHEVCHENKA, saved.place)
    }

    @Test
    fun editLoadsAndChangesTimeAndPlace() {
        val repo = loadedRepository(task(1, dueDate = TODAY).copy(time = LocalTime(9, 0), place = SHEVCHENKA))
        val vm = TaskEditorViewModel(1, repo, validator, fixedDateProvider)
        assertEquals(LocalTime(9, 0), vm.uiState.value.time)
        assertEquals(SHEVCHENKA, vm.uiState.value.place)
        vm.onTimeChange(null) // "No time"
        vm.onPlaceChange(null) // "Clear place"
        assertTrue(vm.save())
        assertNull(repo.task(1)?.time)
        assertNull(repo.task(1)?.place)
    }

    @Test
    fun editingOtherFieldsKeepsTimeAndPlace() {
        val repo = loadedRepository(task(1, dueDate = TODAY).copy(time = LocalTime(9, 0), place = SHEVCHENKA))
        val vm = TaskEditorViewModel(1, repo, validator, fixedDateProvider)
        vm.onTitleChange("Renamed")
        assertTrue(vm.save())
        assertEquals(LocalTime(9, 0), repo.task(1)?.time)
        assertEquals(SHEVCHENKA, repo.task(1)?.place)
    }
}

class PlaceSearchViewModelTest : ViewModelTest() {
    private class FakeGeocoder : Geocoder {
        val queries = mutableListOf<Pair<String, String>>()
        var failing = false
        var results = listOf(SHEVCHENKA)
        override suspend fun reverse(point: GeoPoint): String? = null
        override suspend fun search(query: String, language: String): List<Place> {
            queries += query to language
            if (failing) error("offline")
            return results
        }
    }

    @Test
    fun shortQueriesDoNotSearch() = vmTest {
        val geocoder = FakeGeocoder()
        val vm = track(PlaceSearchViewModel(geocoder, "en"))
        vm.onQueryChange("sh")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(geocoder.queries.isEmpty())
        assertEquals(PlaceSearchUiState(query = "sh"), vm.uiState.value)
    }

    @Test
    fun searchesAfter500msOfSilenceWithOnlyTheLatestQuery() = vmTest {
        val geocoder = FakeGeocoder()
        val vm = track(PlaceSearchViewModel(geocoder, "uk"))
        vm.onQueryChange("She")
        advanceTimeBy(300)
        vm.onQueryChange("Shev")
        assertTrue(vm.uiState.value.isSearching)
        advanceTimeBy(499)
        runCurrent()
        assertTrue(geocoder.queries.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("Shev" to "uk"), geocoder.queries)
        assertEquals(listOf(SHEVCHENKA), vm.uiState.value.results)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun emptyAndFailedResultsAreDistinguished() = vmTest {
        val geocoder = FakeGeocoder().apply { results = emptyList() }
        val vm = track(PlaceSearchViewModel(geocoder, "en"))
        vm.onQueryChange("zzzz")
        advanceTimeBy(501)
        runCurrent()
        assertTrue(vm.uiState.value.noResults)
        geocoder.failing = true
        vm.onRetry()
        advanceTimeBy(501)
        runCurrent()
        assertTrue(vm.uiState.value.failed)
        assertFalse(vm.uiState.value.noResults)
    }
}
