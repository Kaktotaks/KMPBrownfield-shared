package com.example.tasks.shared.data

import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TodoMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/** Source of tasks. Interface → можна підмінити фейком у тестах (і зі Swift, і з Kotlin). */
interface TaskApi {
    /** Fetches todos and maps them to domain tasks using the spec rule (TodoMapper). */
    suspend fun fetchTasks(today: LocalDate): List<Task>
}

/**
 * ⚡️ Ktor замінює дві реалізації: HttpURLConnection (Android) і URLSession (iOS).
 * Сам HTTP-код спільний; платформним лишається тільки engine
 * (OkHttp / Darwin), який підтягується з androidMain / iosMain залежностей.
 */
class KtorTaskApi internal constructor(
    private val client: HttpClient,
    private val endpoint: String,
) : TaskApi {

    // Публічний конструктор без параметрів — зручний і для Kotlin, і для Swift (KtorTaskApi()).
    constructor() : this(createHttpClient(), DEFAULT_ENDPOINT)

    override suspend fun fetchTasks(today: LocalDate): List<Task> =
        client.get(endpoint).body<List<TodoDto>>().map { dto ->
            TodoMapper.map(dto.id, dto.title, dto.completed, today)
        }

    companion object {
        const val DEFAULT_ENDPOINT = "https://jsonplaceholder.typicode.com/todos?_limit=20"
    }
}

internal val TasksJson = Json { ignoreUnknownKeys = true }

/** HttpClient без явного engine: Ktor бере той, що є на платформі (OkHttp / Darwin, або Mock у тестах). */
internal fun createHttpClient(): HttpClient = HttpClient {
    expectSuccess = true // non-2xx → exception → LoadState.Error
    install(ContentNegotiation) { json(TasksJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 15_000
    }
}
