package com.example.tasks.shared.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * ⚡️ "Сирий" interop без SKIE: Swift не вміє підписатися на Kotlin Flow напряму
 * (Flow — інтерфейс з suspend collect, generic губиться).
 * Тому даємо Swift звичайний callback: repository.watchState { state in ... }.
 * Callback викликається на main thread (MainScope = Dispatchers.Main = main queue).
 *
 * Extension на Kotlin-клас експортується як метод цього класу в Swift.
 * Коли підключимо SKIE, цей файл можна буде видалити: Swift отримає
 * `for await state in repository.state`.
 */
fun TaskRepository.watchState(onChange: (LoadState) -> Unit): WatchHandle {
    val job = state.onEach(onChange).launchIn(MainScope())
    return WatchHandle(job)
}

/** Handle to stop watching. Назва не `Cancellable`, щоб не конфліктувати з Combine у Swift. */
class WatchHandle internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}
