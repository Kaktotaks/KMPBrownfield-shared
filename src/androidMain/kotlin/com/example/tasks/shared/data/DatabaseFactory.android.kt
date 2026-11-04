package com.example.tasks.shared.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.example.tasks.shared.db.TasksDatabase

actual class DatabaseFactory(private val context: Context) {
    actual fun create(): TasksDatabase =
        TasksDatabase(AndroidSqliteDriver(TasksDatabase.Schema, context.applicationContext, DATABASE_NAME))
}

internal const val DATABASE_NAME = "tasks.db"
