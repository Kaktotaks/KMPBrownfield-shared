package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents

// Bridge between Swift `Date` and Kotlin `LocalDate`. iosMain only, where Foundation is available.
// Swift: IosDates.shared.toLocalDate(date: Date()) and IosDates.shared.toDate(localDate: ...)
// (Swift Date ↔ NSDate is bridged automatically).
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

    /** `LocalTime` of [hour]:[minute] (Kotlin top-level constructors are not visible from Swift). */
    fun localTime(hour: Int, minute: Int): LocalTime = LocalTime(hour, minute)

    /** Time of day of [date] in the current calendar/time zone (minute precision). */
    fun toLocalTime(date: NSDate): LocalTime {
        val components = NSCalendar.currentCalendar.components(NSCalendarUnitHour or NSCalendarUnitMinute, fromDate = date)
        return LocalTime(components.hour.toInt(), components.minute.toInt())
    }

    /** [localTime] on [localDate] in the current calendar/time zone (for `DatePicker(.hourAndMinute)`). */
    fun toDate(localDate: LocalDate, localTime: LocalTime): NSDate {
        val components = NSDateComponents().apply {
            year = localDate.year.toLong()
            month = localDate.month.ordinal.toLong() + 1
            day = localDate.day.toLong()
            hour = localTime.hour.toLong()
            minute = localTime.minute.toLong()
        }
        return NSCalendar.currentCalendar.dateFromComponents(components)!!
    }
}
