package com.example.tasks.shared.domain

import kotlin.native.ObjCName

/**
 * Minimal logging hook so shared code never prints. The apps can forward to Logcat / os.Logger.
 * Swift sees `SharedLogger` (a plain `Logger` clashes with `os.Logger`).
 */
@ObjCName(swiftName = "SharedLogger")
fun interface Logger {
    fun log(message: String, error: Throwable?)

    companion object {
        /** Default: discards everything. */
        val NONE: Logger = Logger { _, _ -> }
    }
}
