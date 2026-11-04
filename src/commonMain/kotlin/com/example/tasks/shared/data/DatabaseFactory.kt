package com.example.tasks.shared.data

import com.example.tasks.shared.db.TasksDatabase

/**
 * Creates the on-disk SQLite database ("tasks.db").
 * Android: `DatabaseFactory(context).create()`. iOS (Swift): `DatabaseFactory().create()`.
 * Create it once per process and share it.
 */
expect class DatabaseFactory {
    fun create(): TasksDatabase
}
