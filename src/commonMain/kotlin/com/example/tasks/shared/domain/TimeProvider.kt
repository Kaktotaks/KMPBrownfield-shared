package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** "Now" as a local date-time. A fun interface for Kotlin tests; Swift uses [SystemTimeProvider] / [FixedTimeProvider]. */
fun interface TimeProvider {
    fun now(): LocalDateTime
}

object SystemTimeProvider : TimeProvider {
    override fun now(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
}

/**
 * A frozen clock. It is both a [TimeProvider] (for MapViewModel) and a [DateProvider] (for the
 * repository / validator), so a debug hook can freeze "now" everywhere with one object:
 * Swift `FixedTimeProvider(year: 2026, month: 11, day: 4, hour: 12, minute: 40)`.
 */
class FixedTimeProvider(private val fixed: LocalDateTime) : TimeProvider, DateProvider {
    constructor(year: Int, month: Int, day: Int, hour: Int, minute: Int) :
        this(LocalDate(year, month, day).atTime(LocalTime(hour, minute)))

    override fun now(): LocalDateTime = fixed
    override fun today(): LocalDate = fixed.date
}
