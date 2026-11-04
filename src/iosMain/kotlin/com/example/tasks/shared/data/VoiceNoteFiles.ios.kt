package com.example.tasks.shared.data

import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun platformLastModifiedMs(path: String): Long? {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
    val date = attributes[NSFileModificationDate] as? NSDate ?: return null
    return (date.timeIntervalSince1970 * 1000).toLong()
}
