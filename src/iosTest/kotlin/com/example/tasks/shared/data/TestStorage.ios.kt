package com.example.tasks.shared.data

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.example.tasks.shared.db.TasksDatabase

actual fun createInMemoryDatabase(): TasksDatabase = TasksDatabase(inMemoryDriver(TasksDatabase.Schema))
