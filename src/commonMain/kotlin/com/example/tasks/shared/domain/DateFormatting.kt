package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char

// "d MMM yyyy" (англійські назви місяців), напр. "29 Oct 2026".
// Один формат на обидві платформи замість DateTimeFormatter (Android) і DateFormatter (iOS).
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
