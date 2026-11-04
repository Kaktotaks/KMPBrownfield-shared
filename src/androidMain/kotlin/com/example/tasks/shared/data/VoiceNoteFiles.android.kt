package com.example.tasks.shared.data

import java.io.File

internal actual fun platformLastModifiedMs(path: String): Long? =
    File(path).lastModified().takeIf { it > 0 }
