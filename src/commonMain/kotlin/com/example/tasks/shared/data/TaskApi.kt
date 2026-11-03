package com.example.tasks.shared.data

import com.example.tasks.shared.domain.Task
import com.example.tasks.shared.domain.TodoMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/** Source of tasks. An interface so tests can substitute a fake (from Kotlin and from Swift). */
interface TaskApi {
    /** Fetches todos and maps them to domain tasks using the spec rule (TodoMapper). */
    suspend fun fetchTasks(today: LocalDate): List<Task>
}

/**
 * Ktor replaces two native implementations: HttpURLConnection (Android) and URLSession (iOS).
 * The HTTP code is common; only the engine is platform-specific, provided by the
 * expect/actual [platformHttpEngine] (OkHttp in androidMain, Darwin in iosMain).
 */
class KtorTaskApi internal constructor(
    private val client: HttpClient,
    private val endpoint: String,
) : TaskApi {

    // Public no-arg constructor: convenient from Kotlin and Swift (`KtorTaskApi()`).
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

/**
 * The engine is the only platform-specific part of networking, hence expect/actual:
 * androidMain → OkHttp, iosMain → Darwin (NSURLSession).
 * Chosen explicitly rather than by classpath discovery, so it is predictable on both platforms.
 */
internal expect fun platformHttpEngine(): HttpClientEngine

internal fun createHttpClient(): HttpClient = HttpClient(platformHttpEngine()) {
    expectSuccess = true // non-2xx → exception → LoadState.Error
    install(ContentNegotiation) { json(TasksJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 15_000
    }
}
