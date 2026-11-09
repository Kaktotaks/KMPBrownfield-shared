package com.example.tasks.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.example.tasks.shared.db.TasksDatabase
import com.example.tasks.shared.domain.GeoPoint
import com.example.tasks.shared.domain.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The 0.7 / 0.8 schema (version 1), exactly as it shipped. */
private val V1_DDL = listOf(
    """CREATE TABLE TaskEntity (
        id INTEGER NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        notes TEXT NOT NULL,
        priority TEXT NOT NULL,
        due_date TEXT NOT NULL,
        is_done INTEGER NOT NULL DEFAULT 0,
        voice_file TEXT,
        voice_duration_ms INTEGER,
        voice_waveform TEXT
    )""",
    "CREATE TABLE Meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
    "INSERT INTO Meta(key, value) VALUES ('seeded', '1')",
    "INSERT INTO TaskEntity VALUES (1, 'Plain task', 'notes', 'HIGH', '2026-11-04', 0, NULL, NULL, NULL)",
    "INSERT INTO TaskEntity VALUES (2, 'With voice', '', 'LOW', '2026-11-05', 1, 'note-2.m4a', 4200, '0.100,0.200,0.300')",
)

class MigrationTest {

    private fun v1Driver(): SqlDriver = newBlankDriver().also { driver ->
        V1_DDL.forEach { driver.execute(null, it, 0) }
    }

    @Test
    fun schemaIsVersion2() {
        assertEquals(2L, TasksDatabase.Schema.version)
    }

    @Test
    fun v1DatabaseOpensAsV2WithNullTimeAndPlaceAndIntactVoiceNotes() {
        val driver = v1Driver()
        TasksDatabase.Schema.migrate(driver, 1, 2)
        val queries = TasksDatabase(driver).taskQueries

        val rows = queries.selectAll().executeAsList()
        assertEquals(2, rows.size)
        rows.forEach {
            assertNull(it.due_time)
            assertNull(it.place_lat)
            assertNull(it.place_lng)
            assertNull(it.place_address)
        }
        val voice = rows.first { it.id == 2L }
        assertEquals("note-2.m4a", voice.voice_file)
        assertEquals(4200L, voice.voice_duration_ms)
        assertEquals("0.100,0.200,0.300", voice.voice_waveform)
        assertEquals(1L, voice.is_done)
        assertEquals("Plain task", rows.first { it.id == 1L }.title)
        assertEquals("1", queries.getMeta("seeded").executeAsOne())
    }

    @Test
    fun migratedDatabaseAcceptsTimeAndPlace() {
        val driver = v1Driver()
        TasksDatabase.Schema.migrate(driver, 1, 2)
        val queries = TasksDatabase(driver).taskQueries

        queries.setPlace(48.46, 35.05, "Shevchenka St 9", 1)
        val row = queries.selectById(1).executeAsOne()
        assertEquals(48.46, row.place_lat)
        assertEquals("Shevchenka St 9", row.place_address)
        queries.clearPlace(1)
        assertNull(queries.selectById(1).executeAsOne().place_lat)
    }

    @Test
    fun repositoryReadsMigratedDatabase() {
        val driver = v1Driver()
        TasksDatabase.Schema.migrate(driver, 1, 2)
        val database = TasksDatabase(driver)
        val f = RepoFixture()
        try {
            val repository = TaskRepository(StaticApi(emptyList()), com.example.tasks.shared.domain.fixedDateProvider, database, f.files, f.scope)
            val task = assertNotNull(repository.task(2))
            assertNull(task.time)
            assertNull(task.place)
            assertEquals("note-2.m4a", task.voiceNote?.fileName)
            repository.setPlace(2, Place(GeoPoint(48.46, 35.05), "Shevchenka St 9"))
            assertEquals("Shevchenka St 9", repository.task(2)?.place?.address)
        } finally {
            f.close()
        }
    }
}
