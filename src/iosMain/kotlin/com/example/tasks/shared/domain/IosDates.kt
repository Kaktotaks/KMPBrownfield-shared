package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents

// ⚡️ Міст між Swift `Date` і Kotlin `LocalDate`. Тільки в iosMain: тут доступний Foundation.
// У Swift: IosDates.shared.toLocalDate(date: Date()) і IosDates.shared.toDate(localDate: ...)
// (Swift Date ↔ NSDate бріджиться автоматично).
object IosDates {

    /** Calendar day of [date] in the current calendar/time zone. */
    fun toLocalDate(date: NSDate): LocalDate {
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
            fromDate = date,
        )
        return LocalDate(components.year.toInt(), components.month.toInt(), components.day.toInt())
    }

    /** Start of [localDate] in the current calendar/time zone. */
    fun toDate(localDate: LocalDate): NSDate {
        val components = NSDateComponents().apply {
            year = localDate.year.toLong()
            month = localDate.month.ordinal.toLong() + 1
            day = localDate.day.toLong()
        }
        return NSCalendar.currentCalendar.dateFromComponents(components)!!
    }
}
