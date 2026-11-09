package com.example.tasks.shared.data

import app.cash.sqldelight.db.SqlDriver

/** An empty in-memory SQLite database with no schema (for building an old-version database by hand). */
expect fun newBlankDriver(): SqlDriver
