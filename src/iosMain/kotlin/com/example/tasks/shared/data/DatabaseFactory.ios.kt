package com.example.tasks.shared.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.example.tasks.shared.db.TasksDatabase

actual class DatabaseFactory {
    actual fun create(): TasksDatabase =
        TasksDatabase(NativeSqliteDriver(TasksDatabase.Schema, DATABASE_NAME))
}

internal const val DATABASE_NAME = "tasks.db"
