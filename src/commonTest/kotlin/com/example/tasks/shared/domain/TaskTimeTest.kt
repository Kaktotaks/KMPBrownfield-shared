package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sample "now" of the design and the spec: Wed 4 Nov 2026, 12:40. */
val NOW = LocalDateTime(2026, 11, 4, 12, 40)
val NOV_4 = LocalDate(2026, 11, 4)

class TaskTimeTest {
    private fun at(h: Int, m: Int, done: Boolean = false, date: LocalDate = NOV_4) =
        task(1, dueDate = date, isDone = done).copy(time = LocalTime(h, m))

    @Test
    fun scheduledAtCombinesDateAndTime() {
        assertEquals(LocalDateTime(2026, 11, 4, 9, 30), at(9, 30).scheduledAt)
        assertNull(task(1).scheduledAt)
    }

    @Test
    fun pastIsAgainstTheScheduledDateAndTime() {
        assertTrue(at(9, 30).isPast(NOW))
        assertTrue(at(11, 0).isPast(NOW))
        assertFalse(at(13, 15).isPast(NOW))
        assertFalse(at(12, 40).isPast(NOW)) // exactly now is not past
        assertTrue(at(23, 0, date = NOV_4.let { LocalDate(2026, 11, 3) }).isPast(NOW))
        assertFalse(at(1, 0, date = LocalDate(2026, 11, 5)).isPast(NOW))
    }

    @Test
    fun taskWithoutTimeIsNeverPast() {
        assertFalse(task(1, dueDate = LocalDate(2026, 11, 3)).isPast(NOW))
        assertFalse(task(1, dueDate = LocalDate(2026, 11, 3)).isOverdueAt(NOW))
    }

    @Test
    fun overdueIsPastAndNotDone() {
        assertTrue(at(11, 0).isOverdueAt(NOW))
        assertFalse(at(9, 30, done = true).isOverdueAt(NOW))
        assertFalse(at(13, 15).isOverdueAt(NOW))
    }

    @Test
    fun existingConstructorsStillWork() {
        val t = Task(1, "T", "", Priority.LOW, NOV_4, false)
        assertNull(t.time)
        assertNull(t.place)
        val v = Task(1, "T", "", Priority.LOW, NOV_4, false, null)
        assertEquals(t, v)
    }
}
