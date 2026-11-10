package com.example.tasks.shared.data

import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.day
import com.example.tasks.shared.domain.task
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskRepositoryPlaceTest {
    private val fixtures = mutableListOf<RepoFixture>()
    private val place = Place(GeoPoint(48.4560234, 35.0571877), "Shevchenka St 9")

    @AfterTest
    fun tearDown() = fixtures.forEach { it.close() }

    private fun repo(vararg tasks: com.example.tasks.shared.domain.Task) =
        RepoFixture().also { fixtures += it }.seeded(*tasks).repository

    @Test
    fun addStoresTimeAndPlace() {
        val repository = repo()
        val added = repository.add("Dentist", "", Priority.HIGH, TODAY, null, LocalTime(13, 15), place)
        val read = repository.task(added.id)
        assertEquals(LocalTime(13, 15), read?.time)
        assertEquals(place, read?.place)
    }

    @Test
    fun oldAddOverloadsLeaveTimeAndPlaceEmpty() {
        val repository = repo()
        val added = repository.add("Plain", "", Priority.LOW, TODAY)
        assertNull(repository.task(added.id)?.time)
        assertNull(repository.task(added.id)?.place)
    }

    @Test
    fun updateWritesTimeAndPlaceAndKeepsTheVoiceNote() {
        val note = com.example.tasks.shared.domain.VoiceNote("a.m4a", 1000, List(40) { 0.5f })
        val repository = repo(task(1).copy(voiceNote = note))
        repository.update(repository.task(1)!!.copy(time = LocalTime(9, 5), place = place))
        val read = repository.task(1)!!
        assertEquals(LocalTime(9, 5), read.time)
        assertEquals(place, read.place)
        assertEquals("a.m4a", read.voiceNote?.fileName)
        repository.update(read.copy(time = null, place = null))
        assertNull(repository.task(1)?.time)
        assertNull(repository.task(1)?.place)
    }

    @Test
    fun setAndClearPlaceKeepTheTime() {
        val repository = repo(task(1).copy(time = LocalTime(10, 0)))
        repository.setPlace(1, place)
        assertEquals(place, repository.task(1)?.place)
        assertEquals(LocalTime(10, 0), repository.task(1)?.time)
        repository.clearPlace(1)
        assertNull(repository.task(1)?.place)
        assertEquals(LocalTime(10, 0), repository.task(1)?.time)
    }

    @Test
    fun tasksOnEmitsOnlyThatDay() = runTest {
        val repository = repo(task(1, dueDate = TODAY), task(2, dueDate = day(1)), task(3, dueDate = TODAY))
        assertEquals(listOf(1, 3), repository.tasksOn(TODAY).first().map { it.id })
        assertEquals(listOf(2), repository.tasksOn(day(1)).first().map { it.id })
        assertEquals(emptyList(), repository.tasksOn(day(2)).first())
    }
}
