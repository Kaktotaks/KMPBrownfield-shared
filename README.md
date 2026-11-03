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
  │               TaskEditorViewModel, StatsViewModel            │
  │               (androidx.lifecycle ViewModel, StateFlow UiState)│
  │ data          TaskApi / KtorTaskApi, TaskRepository          │
  │               (StateFlow<LoadState>)                         │
  │ domain        Task, Priority, TaskValidator, TaskFilter,     │
  │               StatsCalculator, DateProvider, TodoMapper, ... │
  └──────────────────────────────────────────────────────────────┘
     androidMain: OkHttp engine          iosMain: Darwin engine, IosDates,
                                                  TasksViewModel.clear()
        │ Maven (GitHub Packages)                │ XCFramework + Package.swift (SPM)
        ▼                                        ▼
  android-app: Compose UI, navigation,     ios-app: SwiftUI views, Swift ViewModels,
  ViewModel factories                      SharedViewModel bridge
```

### What's inside
- **domain** (`com.example.tasks.shared.domain`): `Task` (exported to Swift as `TaskItem`),
  `Priority`, `ValidationError`, `TaskValidator`, `TaskFilter` (+ `StatusFilter`, `SortOrder`),
  `StatsCalculator` (+ `TaskStats`), `DateProvider` (+ `SystemDateProvider`), `TodoMapper`,
  `DateFormatting`. `iosMain` adds `IosDates` (Swift `Date` ↔ `LocalDate`).
- **data** (`...shared.data`): `TaskApi` + `KtorTaskApi` (Ktor client, kotlinx.serialization,
  `jsonplaceholder.typicode.com/todos`), internal `TodoDto`, `TaskRepository` — single in-memory
  source of truth exposing `state: StateFlow<LoadState>` (`Loading` / `Error` / `Loaded(tasks)`).
  The HTTP engine is chosen with `expect`/`actual` `platformHttpEngine()`: OkHttp on Android,
  Darwin (NSURLSession) on iOS.
- **presentation** (`...shared.presentation`): `TaskListViewModel`, `TaskDetailViewModel(taskId)`,
  `TaskEditorViewModel(taskId | NEW_TASK_ID)`, `StatsViewModel`, all extending `TasksViewModel`
  (multiplatform `androidx.lifecycle.ViewModel`). Each exposes `uiState: StateFlow<...>` plus event
  methods (`onQueryChange`, `onToggle`, `save()`, ...).
  - Android: regular androidx ViewModels (`ViewModelProvider`, survive configuration changes).
  - iOS: there is no `ViewModelStoreOwner`, so the Swift wrapper calls `viewModel.clear()`
    (iosMain extension) on deinit to cancel `viewModelScope`.

### Stack
Kotlin 2.4.21 · AGP 9.4.1 (`com.android.kotlin.multiplatform.library`) · Gradle 9.6.0 ·
SKIE 0.10.16 · Ktor 3.6.1 · kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.12.0 ·
kotlinx-datetime 0.8.0 · androidx lifecycle-viewmodel 2.11.0.
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

Tests live once in `src/commonTest` (34 tests: validator, filter, stats, mapping, date formatting,
`KtorTaskApi` against Ktor `MockEngine`, `TaskRepository`, all four ViewModels with a test
`Dispatchers.Main`) and run on both platforms.

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
