package com.example.tasks.shared

import com.example.tasks.shared.data.DatabaseFactory
import com.example.tasks.shared.data.KtorTaskApi
import com.example.tasks.shared.data.TaskApi
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.data.VoiceNoteFiles
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Logger
import com.example.tasks.shared.domain.SystemDateProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * iOS wiring in one place (Swift: `TasksIos.shared.createRepository()`); the voice-note files come
 * from `repository.voiceFiles`.
 */
object TasksIos {
    /** `Application Support/VoiceNotes`, created and excluded from iCloud / device backup. */
    @OptIn(ExperimentalForeignApi::class)
    fun voiceNotesDirectory(): String {
        val manager = NSFileManager.defaultManager
        val support = manager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        val directory = support!!.URLByAppendingPathComponent("VoiceNotes", isDirectory = true)!!
        manager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
        directory.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        return directory.path!!
    }

    fun voiceNoteFiles(): VoiceNoteFiles = VoiceNoteFiles(voiceNotesDirectory())

    /** Production wiring: Ktor API, system date, no logging. Call once per process. */
    fun createRepository(): TaskRepository = createRepository(KtorTaskApi(), SystemDateProvider, Logger.NONE)

    fun createRepository(api: TaskApi, dateProvider: DateProvider, logger: Logger): TaskRepository = TaskRepository(
        api = api,
        dateProvider = dateProvider,
        database = DatabaseFactory().create(),
        voiceFiles = voiceNoteFiles(),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        logger = logger,
    )
}
