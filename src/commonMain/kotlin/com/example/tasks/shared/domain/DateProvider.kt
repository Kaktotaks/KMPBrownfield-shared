package com.example.tasks.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

// Renamed from `Clock`: the name is taken in Kotlin (kotlin.time.Clock) and in Swift
// (Swift Concurrency's Clock protocol). Swift sees this interface as the ObjC protocol `DateProvider`.
fun interface DateProvider {
    fun today(): LocalDate
}

// The real implementation lives in commonMain so Swift doesn't have to implement the Kotlin
// interface (a Swift class conforming to an ObjC protocol must subclass NSObject).
// Swift: SystemDateProvider.shared
object SystemDateProvider : DateProvider {
    override fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
}
