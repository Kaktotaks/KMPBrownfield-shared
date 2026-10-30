# shared — Kotlin Multiplatform library for the Tasks apps

Step 1 of the brownfield migration: the **domain layer** that was duplicated in
`android-app` (Kotlin) and `ios-app` (Swift) now lives here once.

| Target | Output | Consumed by |
|---|---|---|
| Android | AAR (`com.example.tasks:shared`) | `android-app` via composite build (`includeBuild("../shared")`) |
| iosArm64 / iosSimulatorArm64 | `Shared.framework` (static) | `ios-app` via direct integration (`embedAndSignAppleFrameworkForXcode`) |

## What's inside (`src/commonMain/.../domain`)
Task, Priority, ValidationError, TaskValidator, TaskFilter (+ StatusFilter, SortOrder),
StatsCalculator (+ TaskStats), DateProvider (+ SystemDateProvider), TodoMapper, DateFormatting.
`src/iosMain` adds `IosDates` (Swift `Date` ↔ `LocalDate`).

Changes vs the native versions:
- `java.time.LocalDate` / `Date` → `kotlinx.datetime.LocalDate`
- `Clock` → `DateProvider` (name clashed with `kotlin.time.Clock` and Swift's `Clock`)
- `Task` is exposed to Swift as `TaskItem` (`@ObjCName`), to avoid clashing with Swift Concurrency `Task`
- The DTO → Task mapping rule moved here as `TodoMapper`; JSON parsing stays native for now

## Data layer (`src/commonMain/.../data`) — step 2
- `TaskApi` + `KtorTaskApi` — Ktor client; engine per platform: OkHttp (androidMain), Darwin/NSURLSession (iosMain)
- `TodoDto` (internal, kotlinx.serialization) — one copy instead of `@Serializable` + `Codable`
- `TaskRepository` + `LoadState` — single in-memory source of truth, `StateFlow<LoadState>`
- iOS observes `TaskRepository.state` via SKIE: `for await state in repository.state`

## SKIE (step 3)
Plugin `co.touchlab.skie` generates Swift wrappers into `Shared.framework` (iOS only):
`suspend` → `async` (any thread, cancellation), `Flow` → `AsyncSequence`, sealed → `onEnum(of:)`,
Kotlin enums → Swift enums. Replaced the hand-written `watchState` callback bridge.

## Presentation layer (`src/commonMain/.../presentation`) — step 4
ViewModels shared by both apps, built on Google's multiplatform `androidx.lifecycle.ViewModel`:
`TaskListViewModel`, `TaskDetailViewModel(taskId)`, `TaskEditorViewModel(taskId | NEW_TASK_ID)`, `StatsViewModel`.
Each exposes `uiState: StateFlow<...>` + event methods (`onQueryChange`, `save()`, ...).
- Android: regular androidx ViewModels (ViewModelProvider, survive configuration changes).
- iOS: observed via SKIE (`for await state in vm.uiState`); no ViewModelStoreOwner on iOS, so the
  Swift wrapper calls `viewModel.clear()` (iosMain) on deinit to cancel `viewModelScope`.
- Tested once in `commonTest/.../presentation` (Dispatchers.Main replaced by a test dispatcher).

## Distribution — step 6 (option A: `Package.swift` lives in this repo)

One version (a git tag like `0.5.0`) is shipped two ways:

| Consumer | Artifact | Where |
|---|---|---|
| iOS (SPM) | `Shared.xcframework.zip` + `Package.swift` (`binaryTarget(url:checksum:)`) | GitHub Release `0.5.0` |
| Android (Gradle) | `com.example.tasks:shared:0.5.0` (+ `shared-android`, Gradle module metadata) | GitHub Packages (Maven) |

- `.github/workflows/ci.yml` runs `allTests`, builds a debug XCFramework and validates `Package.swift` on every PR and push to `main`.
- `.github/workflows/release.yml` runs by hand (`gh workflow run release.yml -f version=0.5.0`): tests, builds the XCFramework, computes the checksum, commits `Package.swift`, tags, creates the GitHub Release and publishes to Maven.
- `version` and `checksum` in `Package.swift` are written by CI only.

### Consuming a release
- **iOS:** Xcode → Add Package Dependencies → `https://github.com/Kaktotaks/KMPBrownfield-shared`, product `Shared`.
- **Android:** GitHub Packages needs a token even for public packages. Put a classic PAT with `read:packages` into `~/.gradle/gradle.properties`:
  ```properties
  GitHubPackagesUsername=<github-login>
  GitHubPackagesPassword=<token>
  ```

### Local development (changing shared and an app together)
- **Android:** `-PuseLocalShared=true` (or `useLocalShared=true` in `~/.gradle/gradle.properties`) switches android-app back to `includeBuild("../shared")`.
- **iOS:** `./gradlew assembleSharedDebugXCFramework`, then in Xcode replace the remote package with *Add Local…* → this folder. `Package.swift` picks `build/XCFrameworks/debug/Shared.xcframework` when it exists. Re-run the Gradle task after Kotlin changes.

## Commands
```sh
./gradlew allTests                              # common tests on Android host + iOS simulator
./gradlew assembleSharedDebugXCFramework        # build/XCFrameworks/debug/Shared.xcframework
./gradlew assembleSharedReleaseXCFramework      # what CI zips for a release
./gradlew publishToMavenLocal                   # try the Maven publication locally (~/.m2)
swift package dump-package                      # validate Package.swift
```
