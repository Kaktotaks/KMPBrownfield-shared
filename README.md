# KMPBrownfield-shared

Kotlin Multiplatform library with the **domain, data and presentation layers** of a small Tasks
app. It is the shared core of a brownfield migration: two existing native apps — Android (Jetpack
Compose) and iOS (SwiftUI) — were moved onto one Kotlin codebase step by step, while their UI stayed
native.

| Repository | What it is |
|---|---|
| **[KMPBrownfield-shared](https://github.com/Kaktotaks/KMPBrownfield-shared)** (this repo) | KMP library: domain, data (Ktor), shared ViewModels; ships an XCFramework (SPM) and a Maven artifact |
| [KMPBrownfield-android](https://github.com/Kaktotaks/KMPBrownfield-android) | Jetpack Compose app, consumes `com.example.tasks:shared` from GitHub Packages |
| [KMPBrownfield-ios](https://github.com/Kaktotaks/KMPBrownfield-ios) | SwiftUI app, consumes the `Shared` XCFramework via Swift Package Manager |

Product spec (screens, business rules, network mapping): [`docs/SPEC.md`](docs/SPEC.md).
How the code got here: [`docs/MIGRATION.md`](docs/MIGRATION.md) (brownfield migration log, steps 0–6).

## Architecture

```
                 KMPBrownfield-shared (commonMain)
  ┌──────────────────────────────────────────────────────────────┐
  │ presentation  TaskListViewModel, TaskDetailViewModel,        │
  │               TaskEditorViewModel, StatsViewModel,           │
  │               VoiceNoteViewModel                             │
  │               (androidx.lifecycle ViewModel, StateFlow UiState)│
  │ data          TaskApi / KtorTaskApi, TaskRepository          │
  │               (offline-first, SQLDelight), VoiceNoteFiles    │
  │ domain        Task, Priority, TaskValidator, TaskFilter,     │
  │               StatsCalculator, DateProvider, TodoMapper, ... │
  └──────────────────────────────────────────────────────────────┘
     androidMain: OkHttp, SQLite driver  iosMain: Darwin, SQLite driver, IosDates,
                  TasksAndroid                    TasksIos, TasksViewModel.clear()
        │ Maven (GitHub Packages)                │ XCFramework + Package.swift (SPM)
        ▼                                        ▼
  android-app: Compose UI, navigation,     ios-app: SwiftUI views, Swift ViewModels,
  ViewModel factories                      SharedViewModel bridge
```

### What's inside
- **domain** (`com.example.tasks.shared.domain`): `Task` (exported to Swift as `TaskItem`),
  `Priority`, `ValidationError`, `TaskValidator`, `TaskFilter` (+ `StatusFilter`, `SortOrder`),
  `StatsCalculator` (+ `TaskStats`), `DateProvider` (+ `SystemDateProvider`), `TodoMapper`,
  `DateFormatting`, `VoiceNote`, `VoiceNoteLimits`, `VoiceNoteFormat`, `Logger`, and the native audio
  adapter interfaces (`AudioRecorder`, `AudioPlayer`, `MicPermissionProvider`).
  `iosMain` adds `IosDates` (Swift `Date` ↔ `LocalDate`).
- **data** (`...shared.data`): `TaskApi` + `KtorTaskApi` (Ktor client, kotlinx.serialization,
  `jsonplaceholder.typicode.com/todos`), internal `TodoDto`, `TaskRepository` — offline-first source
  of truth backed by SQLite (see [Persistence](#persistence)), exposing
  `state: StateFlow<LoadState>` (`Loading` / `Error` / `Loaded(tasks)`); `DatabaseFactory`
  (`expect`/`actual`), `VoiceNoteFiles`.
  The HTTP engine is chosen with `expect`/`actual` `platformHttpEngine()`: OkHttp on Android,
  Darwin (NSURLSession) on iOS.
- **motion** (`...shared.motion`): `MotionSpec` (`emphasized` easing, `Splash` timeline constants,
  `frameAt` / `exitStartMs`), `CubicBezier`, `SpringParams`, `SplashFrame`. Pure functions of time
  that both apps draw for the animated splash. Swift API: [`docs/motion-spec-swift-api.md`](docs/motion-spec-swift-api.md).
- **presentation** (`...shared.presentation`): `TaskListViewModel`, `TaskDetailViewModel(taskId)`,
  `TaskEditorViewModel(taskId | NEW_TASK_ID)`, `StatsViewModel`, `VoiceNoteViewModel`, all extending `TasksViewModel`
  (multiplatform `androidx.lifecycle.ViewModel`). Each exposes `uiState: StateFlow<...>` plus event
  methods (`onQueryChange`, `onToggle`, `save()`, ...).
  - Android: regular androidx ViewModels (`ViewModelProvider`, survive configuration changes).
  - iOS: there is no `ViewModelStoreOwner`, so the Swift wrapper calls `viewModel.clear()`
    (iosMain extension) on deinit to cancel `viewModelScope`.

### Stack
Kotlin 2.4.21 · AGP 9.4.1 (`com.android.kotlin.multiplatform.library`) · Gradle 9.6.0 ·
SKIE 0.10.16 · Ktor 3.6.1 · kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.12.0 ·
kotlinx-datetime 0.8.0 · androidx lifecycle-viewmodel 2.11.0 · SQLDelight 2.4.1 · kotlinx-io 0.9.2.
Targets: Android (minSdk 24, compileSdk 37), `iosArm64`, `iosSimulatorArm64`.

### Swift API notes
SKIE (`co.touchlab.skie`) generates Swift wrappers into the framework, so the Kotlin API reads
naturally from Swift:
- `suspend` → `async` (callable from any thread, Swift `Task` cancellation cancels the coroutine):
  `try await repository.load()`.
- `Flow` / `StateFlow` → typed `AsyncSequence`: `for await state in vm.uiState { ... }`,
  `vm.uiState.value` is typed.
- sealed types → `switch onEnum(of: state) { case .loading: ... }` (exhaustive).
- Kotlin enums → real Swift enums (exhaustive `switch`, but not `CaseIterable`).

Plain Kotlin/Native export rules still apply:
- Kotlin `Int` is `Int32` in Swift (ids, stats), `Long` is `Int64`.
- `object`s are reached via `.shared` (`TaskFilter.shared.apply(...)`), companions via `.companion`
  (`TaskEditorViewModel.companion.NEW_TASK_ID`).
- Default arguments are not exported: Swift passes every argument (e.g. `originalDueDate: nil`).
- Name clashes are avoided on the Kotlin side: `Task` → `TaskItem` (`@ObjCName`, clashes with Swift
  Concurrency `Task`), `Clock` → `DateProvider`.
- kotlinx-datetime `LocalDate` appears as `Kotlinx_datetimeLocalDate`; the iOS app aliases it.
- Implementing a Kotlin interface with `suspend` members in Swift (e.g. a fake `TaskApi`) means
  implementing SKIE's `__`-prefixed requirement (`__fetchTasks(today:)`).

## Persistence

Tasks live in a SQLite database, accessed through [SQLDelight](https://sqldelight.github.io/sqldelight/)
(`TasksDatabase`, schema in `src/commonMain/sqldelight/.../Task.sq`, version 1).

- **The database is the source of truth.** `TaskRepository.state` is the `TaskEntity` table observed
  as a hot `StateFlow` (`stateIn(scope, Eagerly, Loading)`), so every write reaches every screen.
- **Offline-first.** The first `load()` fetches from the API and seeds the table plus a
  `Meta.seeded = 1` flag in one transaction. After that `load()` never touches the network: tasks,
  edits and done state survive restarts and work offline. First launch without a network (and an
  empty table) is `LoadState.Error`; Retry calls `load()` again.
- **`load()` is guarded by a `Mutex`.** Concurrent calls (for example a double tap on Retry) fetch
  once; this fixes the old "double Retry triggers two loads" issue as a side effect.
- **Writes** (`add`, `update`, `toggle`, `delete`, `setVoiceNote`, `clearVoiceNote`) are plain
  non-suspend calls, each one small transaction that completes before the call returns.
  `add` computes `id = max(id) + 1` inside the transaction (no race between two adds).
- **Wiring.** Android: `TasksAndroid.createRepository(context)`. iOS: `TasksIos.shared.createRepository()`.
  Or by hand: `TaskRepository(api, dateProvider, DatabaseFactory(...).create(), voiceFiles, scope)`
  (an optional last argument takes a `Logger`; shared never prints).
- **Migrations.** `verifyMigrations` is on and `src/commonMain/sqldelight/databases/1.db` is the
  committed schema-v1 snapshot. A schema change adds `src/commonMain/sqldelight/migrations/1.sqm`
  (version 1 → 2); `./gradlew verifyCommonMainTasksDatabaseMigration` (part of `check`) proves the
  migration produces the same schema as a fresh install.

### The SQLite linkage gotcha (iOS)
SQLDelight's `NativeSqliteDriver` calls the system `libsqlite3`, so the final app has to link with
`-lsqlite3`. We ship Kotlin as a **static** framework inside an XCFramework, and a static framework
carries no linker flags: without help, the app fails to link with `Undefined symbols: _sqlite3_open_v2`.
SwiftPM does not offer a way out on the `binaryTarget` either, because it cannot declare
`linkerSettings`.

The fix lives in `Package.swift`: a tiny regular target `SharedLinkage` (one empty Swift file under
`Sources/SharedLinkage/`) with `linkerSettings: [.linkedLibrary("sqlite3")]`, and the `Shared`
product is `targets: ["Shared", "SharedLinkage"]`. Anything that links the `Shared` product gets the
flag. Apps added through SwiftPM need no manual "Link Binary With Libraries" step. (Consuming the
XCFramework without SwiftPM requires adding `libsqlite3.tbd` yourself.)

## Voice notes

A task can have one optional voice note (up to 2:00). **All recorder/player logic is in shared**; the
apps provide only thin native adapters and the UI.

- **Model.** `VoiceNote(fileName, durationMs, waveform)` (`Task.voiceNote`); limits in
  `VoiceNoteLimits` (120 s max, warning from 110 s, 50 ms tick, 32 live bars, 40 stored bars);
  `VoiceNoteFormat.duration(ms)` formats `m:ss` the same on both platforms.
  Only the file **name** is stored (iOS container paths change between installs).
- **Files.** `VoiceNoteFiles(baseDirectory)` (kotlinx-io `SystemFileSystem`): drafts are
  `draft-<uuid>.m4a`, saved notes `note-<uuid>.m4a`. Android directory: `filesDir/voice_notes`;
  iOS: `Application Support/VoiceNotes` (`TasksIos.voiceNotesDirectory()`, excluded from backup).
  Deleting a task, clearing or replacing its note deletes the file; on startup the repository removes
  files no row references (and `draft-*` files older than a day).
- **Adapters the apps implement** (plain non-suspend interfaces, so Swift implements them without
  SKIE's `__` prefix): `AudioRecorder` (AAC/m4a, mono, 44.1 kHz, 64 kbps), `AudioPlayer`,
  `MicPermissionProvider`.
- **`VoiceNoteViewModel(target, repository, files, recorder, player, permission)`** drives the whole
  card: `uiState: StateFlow<VoiceNoteUiState>` (`Empty`, `PermissionExplainer`, `PermissionDenied`,
  `Recording`, `Ready`, `Error`) plus `confirmation: StateFlow<VoiceNoteConfirmation?>`
  (`REPLACE`, `DELETE`) for the system alerts. Targets: `ExistingTask(taskId)` writes to the
  repository immediately; `Draft(initial)` is used by the editor and commits on Save.
  - Recording writes a draft file, ticks every 50 ms (timer, level ring buffer of 32), warns from
    110 s and auto-stops at 120 s. On stop: duration rounded to 100 ms, waveform downsampled to 40
    bars (max per bucket, normalized to the clip's peak, floor 0.08 so silence still shows a bar), draft
    promoted to a note file.
  - Recordings shorter than 1 s are discarded without an error (keeps the UI simple).
  - Playback prepares lazily, ticks the position every 50 ms, resets on completion; seek works while
    paused; speed cycles 1 → 1.5 → 2 → 1.
  - Backgrounding keeps a recording (stop) and pauses playback.
- **Editor.** `TaskEditorUiState.voiceNote`, `TaskEditorViewModel.onVoiceNoteChange(note)`,
  `onCancel()`. Save commits (new / replaced / removed note, old file deleted after a successful
  save); Cancel deletes everything recorded in the session and keeps the original note.
  `voiceVm.bindTo(editorVm)` forwards changes.

## Build and test

Requirements: macOS with Xcode 16+ for the iOS targets (Kotlin/Native for Apple builds only on
macOS); a JDK 17+ to launch Gradle (the daemon provisions JDK 21 itself via
`gradle/gradle-daemon-jvm.properties`). Android-only tasks also work on Linux/Windows.

```sh
./gradlew allTests                               # commonTest on Android host + iOS simulator
./gradlew assembleSharedDebugXCFramework         # build/XCFrameworks/debug/Shared.xcframework
./gradlew assembleSharedReleaseXCFramework       # what the release workflow zips
./gradlew publishToMavenLocal                    # try the Maven publication locally (~/.m2)
swift package dump-package --manifest-cache none # validate Package.swift (bypasses manifest cache)
```

Tests live once in `src/commonTest` (104 tests: validator, filter, stats, mapping, date
formatting, `KtorTaskApi` against Ktor `MockEngine`, `TaskRepository` on an in-memory SQLite
database, `VoiceNoteFiles`, waveform, `VoiceNoteViewModel` with fake recorder/player/permission under
virtual time, the editor's voice-note semantics, all ViewModels with a test `Dispatchers.Main`) and
run on both platforms. The in-memory database is a JDBC driver on the Android host and the native
driver on the iOS simulator (`createInMemoryDatabase()` is an `expect`/`actual` test helper).

## Distribution and versioning

One version — a git tag such as `0.5.0` — is shipped two ways:

| Consumer | Artifact | Where |
|---|---|---|
| iOS (SPM) | `Shared.xcframework.zip` + root `Package.swift` (`binaryTarget(url:checksum:)`) | GitHub Release `0.5.0` |
| Android (Gradle) | `com.example.tasks:shared:0.5.0` (+ `shared-android`, Gradle module metadata) | GitHub Packages (Maven) |

- The version comes from the tag: the release workflow passes `-PsharedVersion=X.Y.Z`
  (local builds are `0.0.0-local`).
- `.github/workflows/ci.yml` — on every PR and push to `main`: `allTests`, debug XCFramework build,
  `Package.swift` validation.
- `.github/workflows/release.yml` — manual (`gh workflow run release.yml -f version=0.5.0`): tests →
  release XCFramework → zip + checksum → commit `Package.swift` with the new `version`/`checksum` →
  tag → GitHub Release with the zip → Maven publish. `version` and `checksum` in `Package.swift` are
  written only by this workflow.
- The framework is built with Swift library evolution (`skie { build { produceDistributableFramework() } }`),
  so an XCFramework built on CI works with a different Xcode version in the app.

### Consuming a release
- **iOS:** Xcode → File → Add Package Dependencies → `https://github.com/Kaktotaks/KMPBrownfield-shared`,
  product `Shared`, rule *Up to Next Minor*. No Gradle or JDK needed.
- **Android:** `implementation("com.example.tasks:shared:0.5.0")` with the GitHub Packages repository
  `https://maven.pkg.github.com/Kaktotaks/KMPBrownfield-shared`. GitHub Packages requires a token even
  for public packages — a classic PAT with `read:packages` in `~/.gradle/gradle.properties`:
  ```properties
  GitHubPackagesUsername=<github-login>
  GitHubPackagesPassword=<token>
  ```
  No token? Build the Android app against the sources instead (next section).

### Local development (changing shared and an app together)
Clone the repos side by side; the apps expect this folder to be named `shared`:
```
KMPBrownfield/
  shared/        <- git clone https://github.com/Kaktotaks/KMPBrownfield-shared shared
  android-app/
  ios-app/
```
- **Android:** `./gradlew :app:assembleDebug -PuseLocalShared=true` in `android-app` (or
  `useLocalShared=true` in `~/.gradle/gradle.properties`) switches to `includeBuild("../shared")`.
- **iOS:** `./gradlew assembleSharedDebugXCFramework` here, then in Xcode replace the remote package
  with *Add Local…* → this folder. `Package.swift` uses `build/XCFrameworks/debug/Shared.xcframework`
  when it exists. Re-run the Gradle task after Kotlin changes.
  - SwiftPM caches the evaluated manifest by its *contents*, so it does not notice the debug
    XCFramework appearing or disappearing. After switching, use Xcode → File → Packages → Reset
    Package Caches (CLI: `swift package dump-package --manifest-cache none`).

## AI-assisted workflow

- Development was done with [Claude Code](https://claude.com/claude-code) as a pair programmer;
  such commits carry a `Co-Authored-By: Claude ...` trailer.
- Pull requests are reviewed by an automated Claude reviewer agent,
  [`.claude/agents/swift-reviewer.md`](.claude/agents/swift-reviewer.md), run by
  [`.github/workflows/claude-review.yml`](.github/workflows/claude-review.yml). It focuses on the
  Swift API quality of the KMP surface (name clashes, default arguments, exceptions crossing into
  Swift, type mapping, source-breaking enum/sealed changes, SKIE shapes), common-code correctness
  and tests. The review is posted as one PR comment, and a `Verdict: ⛔️ do not push` line fails the
  check. The same agent can be run locally before pushing.

## License

[MIT](LICENSE) © 2026 Leonid Shevchenko
