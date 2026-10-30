package com.example.tasks.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskValidatorTest {

    private val validator = TaskValidator(fixedDateProvider)

    @Test
    fun validInputHasNoErrors() {
        assertTrue(validator.validate("Buy milk", "", TODAY).isEmpty())
    }

    @Test
    fun titleIsTrimmedBeforeLengthCheck() {
        assertEquals(listOf(ValidationError.TITLE_TOO_SHORT), validator.validate("  ab  ", "", TODAY))
        assertTrue(validator.validate("  abc  ", "", TODAY).isEmpty())
    }

    @Test
    fun titleTooLong() {
        assertTrue(validator.validate("a".repeat(60), "", TODAY).isEmpty())
        assertEquals(listOf(ValidationError.TITLE_TOO_LONG), validator.validate("a".repeat(61), "", TODAY))
    }

    @Test
    fun notesTooLong() {
        assertTrue(validator.validate("Title", "n".repeat(500), TODAY).isEmpty())
        assertEquals(listOf(ValidationError.NOTES_TOO_LONG), validator.validate("Title", "n".repeat(501), TODAY))
    }

    @Test
    fun pastDueDateRejectedForNewTask() {
        assertEquals(listOf(ValidationError.DUE_DATE_IN_PAST), validator.validate("Title", "", day(-1)))
    }

    @Test
    fun unchangedOverdueDateAllowedWhenEditing() {
        assertTrue(validator.validate("Title", "", day(-5), originalDueDate = day(-5)).isEmpty())
    }

    @Test
    fun changedPastDateRejectedWhenEditing() {
        assertEquals(
            listOf(ValidationError.DUE_DATE_IN_PAST),
            validator.validate("Title", "", day(-2), originalDueDate = day(-5)),
        )
    }

    @Test
    fun errorsAreReturnedInSpecOrder() {
        assertEquals(
            listOf(ValidationError.TITLE_TOO_SHORT, ValidationError.NOTES_TOO_LONG, ValidationError.DUE_DATE_IN_PAST),
            validator.validate(" ", "n".repeat(501), day(-1)),
        )
    }

    @Test
    fun messagesMatchSpec() {
        assertEquals("Title must be at least 3 characters.", ValidationError.TITLE_TOO_SHORT.message)
        assertEquals("Title must be at most 60 characters.", ValidationError.TITLE_TOO_LONG.message)
        assertEquals("Notes must be at most 500 characters.", ValidationError.NOTES_TOO_LONG.message)
        assertEquals("Due date can't be in the past.", ValidationError.DUE_DATE_IN_PAST.message)
    }
}
