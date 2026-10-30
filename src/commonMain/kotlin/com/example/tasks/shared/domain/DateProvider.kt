package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

// ⚡️ Перейменовано з `Clock`: це ім'я вже зайняте і в Kotlin (kotlin.time.Clock),
// і в Swift (протокол Clock зі Swift Concurrency).
// У Swift інтерфейс приходить як ObjC-протокол `DateProvider`.
fun interface DateProvider {
    fun today(): LocalDate
}

// Реальна реалізація живе тут, у commonMain, щоб Swift не довелося реалізовувати
// Kotlin-інтерфейс (для ObjC-протоколу Swift-клас мав би наслідуватись від NSObject).
// У Swift: SystemDateProvider.shared
object SystemDateProvider : DateProvider {
    override fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
