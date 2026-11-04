package com.example.tasks.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.tasks.shared.db.TasksDatabase

actual fun createInMemoryDatabase(): TasksDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TasksDatabase.Schema.create(driver)
    return TasksDatabase(driver)
}
