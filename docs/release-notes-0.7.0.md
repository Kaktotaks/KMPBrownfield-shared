Local persistence and voice notes. **Contains a breaking change** to the `TaskRepository` constructor.

## What's new in 0.7.0
- **Offline-first persistence (SQLDelight).** Tasks are stored in SQLite (`TasksDatabase`, schema
  v1); the database is the source of truth and `TaskRepository.state` observes it. The first
  successful `load()` seeds the database, after which the app works offline and survives restarts.
  A first launch without a network is still `LoadState.Error`.
- **Voice notes.** A task can carry one voice note (up to 2:00): `Task.voiceNote`, `VoiceNote`,
  `VoiceNoteLimits`, `VoiceNoteFormat.duration(ms)`, `VoiceNoteFiles` (kotlinx-io), and a
  `VoiceNoteViewModel` that owns the recorder / player state machine (permission explainer, timer,
  warning at 1:50, auto-stop at 2:00, waveform, speed, file lifecycle). The apps implement the native
  adapters `AudioRecorder`, `AudioPlayer`, `MicPermissionProvider`.
- **Editor integration.** `TaskEditorUiState.voiceNote`, `TaskEditorViewModel.onVoiceNoteChange(_:)`
  and `onCancel()`; Save commits the note, Cancel deletes what was recorded in the session.
- **Wiring helpers.** `TasksAndroid.createRepository(context)` and `TasksIos.shared.createRepository()`.
- **SPM:** `Package.swift` adds a `SharedLinkage` target that links the system `sqlite3` (a static
  Kotlin framework cannot carry linker flags). Nothing to do for apps that use the `Shared` product.

## Fixes
- **Double Retry.** `load()` is serialized by a `Mutex`: concurrent calls (e.g. two quick taps on
  Retry) perform a single fetch instead of two.
- **Racy ids.** `add` assigns `max(id) + 1` inside the database transaction.
- No more `println` in shared: errors go to an injectable `Logger` (Swift name `SharedLogger`,
  default: silent).

## Breaking changes
- **`TaskRepository` constructor.** Was `TaskRepository(api, dateProvider)`. Now
  `TaskRepository(api, dateProvider, database, voiceFiles, scope)` (plus an optional trailing
  `logger`). Use `TasksAndroid.createRepository(context)` / `TasksIos.shared.createRepository()`, or
  build `DatabaseFactory(...).create()`, `VoiceNoteFiles(directory)` and an app-lifetime
  `CoroutineScope` yourself.
- **`Task` gained `voiceNote: VoiceNote?`** (last parameter, default `null`). A six-argument
  initializer is kept for Swift, so existing `TaskItem(...)` calls still compile.
- **`TaskEditorUiState` gained `voiceNote: VoiceNote?`** (last parameter, default `null` in Kotlin);
  Swift code that constructs `TaskEditorUiState` must pass it.
- `TaskRepository.update(task)` does **not** write the voice note; use `setVoiceNote` /
  `clearVoiceNote`.
- `TaskRepository.load()` for a seeded database no longer re-fetches: Retry on a healthy app is a no-op.
- Writes are applied to the database before the call returns, but `repository.state` re-emits
  asynchronously (on the scope's dispatcher); read `repository.task(id)` for an immediate view.

## Requirements
Built with Kotlin 2.4.21, SQLDelight 2.4.1, kotlinx-io 0.9.2, AGP 9.4.1, Gradle 9.6.0. iOS 15+, Android minSdk 24.
The Android app needs no extra permission for shared itself; the **apps** declare
`RECORD_AUDIO` / `NSMicrophoneUsageDescription` when they ship the voice-note UI.

## How to consume
See the [README](https://github.com/Kaktotaks/KMPBrownfield-shared#readme): iOS via Swift Package
Manager (product `Shared`, which now bundles the `SharedLinkage` target), Android via
`com.example.tasks:shared:0.7.0` from GitHub Packages.
