package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateFormattingTest {

    @Test
    fun formatsAsDayShortMonthYear() {
        assertEquals("29 Oct 2026", DateFormatting.format(TODAY))
        assertEquals("1 Mar 2027", DateFormatting.format(LocalDate(2027, 3, 1)))
    }
}
