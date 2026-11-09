package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlin.native.ObjCName

// java.time.LocalDate → kotlinx.datetime.LocalDate: java.time is JVM-only, while commonMain
// is also compiled with Kotlin/Native for iOS.
//
// @ObjCName: in Swift the name `Task` clashes with Swift Concurrency's `Task`
// ("'Task' is ambiguous for type lookup"). Kotlin/Android keeps `Task`; Swift sees `TaskItem`.
@ObjCName(swiftName = "TaskItem")
data class Task(
    val id: Int,
    val title: String,
    val notes: String,
    val priority: Priority,
    val dueDate: LocalDate,
    val isDone: Boolean,
    val voiceNote: VoiceNote? = null,
    /** Optional time of day on [dueDate] (minute precision). */
    val time: LocalTime? = null,
    /** Optional place, shown on the Map tab. */
    val place: Place? = null,
) {
    // Source compatibility for Swift (defaults are not exported to ObjC): the pre-0.7 initializer.
    constructor(
        id: Int,
        title: String,
        notes: String,
        priority: Priority,
        dueDate: LocalDate,
        isDone: Boolean,
    ) : this(id, title, notes, priority, dueDate, isDone, null, null, null)

    // Source compatibility for Swift: the 0.7 / 0.8 initializer with a voice note.
    constructor(
        id: Int,
        title: String,
        notes: String,
        priority: Priority,
        dueDate: LocalDate,
        isDone: Boolean,
        voiceNote: VoiceNote?,
    ) : this(id, title, notes, priority, dueDate, isDone, voiceNote, null, null)

    /** "0:42" for the list indicator; null when there is no voice note. */
    val voiceDurationText: String? get() = voiceNote?.durationText

    /** Overdue: not done and due date strictly before today. */
    fun isOverdue(today: LocalDate): Boolean = !isDone && dueDate < today

    /** [dueDate] at [time]; null when the task has no time. */
    val scheduledAt: LocalDateTime? get() = time?.let { dueDate.atTime(it) }

    /** The scheduled date+time is before [now]. A task without a time is never "past". */
    fun isPast(now: LocalDateTime): Boolean = scheduledAt?.let { it < now } ?: false

    /** Past and not done (Map flavour of overdue; the list keeps [isOverdue]). */
    fun isOverdueAt(now: LocalDateTime): Boolean = !isDone && isPast(now)
}
