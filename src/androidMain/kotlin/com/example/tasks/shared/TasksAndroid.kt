package com.example.tasks.shared

import android.content.Context
import com.example.tasks.shared.data.DatabaseFactory
import com.example.tasks.shared.data.KtorTaskApi
import com.example.tasks.shared.data.TaskApi
import com.example.tasks.shared.data.TaskRepository
import com.example.tasks.shared.data.VoiceNoteFiles
import com.example.tasks.shared.domain.DateProvider
import com.example.tasks.shared.domain.Logger
import com.example.tasks.shared.domain.SystemDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Android wiring in one place: `TasksAndroid.createRepository(context)`; files via `repository.voiceFiles`. */
object TasksAndroid {
    /** `context.filesDir/voice_notes`. */
    fun voiceNoteFiles(context: Context): VoiceNoteFiles =
        VoiceNoteFiles(File(context.filesDir, "voice_notes").path)

    /**
     * Opens "tasks.db", builds the repository with the app-lifetime [scope] (default: a new
     * SupervisorJob on Dispatchers.Default). Call once per process.
     */
    @JvmOverloads
    fun createRepository(
        context: Context,
        api: TaskApi = KtorTaskApi(),
        dateProvider: DateProvider = SystemDateProvider,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        logger: Logger = Logger.NONE,
    ): TaskRepository = TaskRepository(
        api = api,
        dateProvider = dateProvider,
        database = DatabaseFactory(context).create(),
        voiceFiles = voiceNoteFiles(context),
        scope = scope,
        logger = logger,
    )
}
