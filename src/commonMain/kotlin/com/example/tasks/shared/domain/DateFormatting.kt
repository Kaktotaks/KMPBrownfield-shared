package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

// "d MMM yyyy" with English month names, e.g. "29 Oct 2026".
// One format for both platforms instead of DateTimeFormatter (Android) and DateFormatter (iOS).
object DateFormatting {
    private val format = LocalDate.Format {
        day(padding = Padding.NONE)
        char(' ')
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        year()
    }

    fun format(date: LocalDate): String = format.format(date)
}
