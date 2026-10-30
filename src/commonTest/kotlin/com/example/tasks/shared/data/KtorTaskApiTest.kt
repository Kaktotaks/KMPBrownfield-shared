package com.example.tasks.shared.data

import com.example.tasks.shared.domain.Priority
import com.example.tasks.shared.domain.TODAY
import com.example.tasks.shared.domain.day
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorTaskApiTest {

    private fun api(status: HttpStatusCode, body: String): KtorTaskApi {
        val engine = MockEngine { request ->
            assertEquals(KtorTaskApi.DEFAULT_ENDPOINT, request.url.toString())
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(TasksJson) }
        }
        return KtorTaskApi(client, KtorTaskApi.DEFAULT_ENDPOINT)
    }

    @Test
    fun parsesTodosAndMapsWithSpecRule() = runTest {
        val json = """[
            {"userId":1,"id":1,"title":"delectus","completed":false,"extra":"ignored"},
            {"userId":1,"id":3,"title":"fugiat","completed":true}
        ]"""
        val tasks = api(HttpStatusCode.OK, json).fetchTasks(TODAY)

        assertEquals(listOf(1, 3), tasks.map { it.id })
        assertEquals("delectus", tasks[0].title)
        assertEquals(Priority.MEDIUM, tasks[0].priority)
        assertEquals(day(-2), tasks[0].dueDate)
        assertEquals(true, tasks[1].isDone)
    }

    @Test
    fun serverErrorThrows() = runTest {
        assertFailsWith<Exception> { api(HttpStatusCode.InternalServerError, "oops").fetchTasks(TODAY) }
    }
}
