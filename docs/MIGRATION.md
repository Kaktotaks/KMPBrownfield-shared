# Brownfield migration log

How two independent native Tasks apps (Android/Compose and iOS/SwiftUI, built from the same
[spec](SPEC.md)) were moved onto one Kotlin Multiplatform core, one layer at a time, with both apps
shippable after every step. App-side details:
[Android log](https://github.com/Kaktotaks/KMPBrownfield-android/blob/main/docs/MIGRATION.md) ·
[iOS log](https://github.com/Kaktotaks/KMPBrownfield-ios/blob/main/docs/MIGRATION.md).

| Step | What moved / changed | shared commits |
|---|---|---|
| 0 | Two native apps from one spec, identical layer names | — |
| 1 | Domain layer → `shared` | `b478413` |
| 2 | Data layer (networking, repository) → `shared` | `e8a9acc` |
| 3 | SKIE for a Swift-friendly iOS API | `11b1750` |
| 4 | ViewModels → `shared` | `398e472` |
| 5 | App-side UI refactor (UI-only screens) + AGP 9.4.1 / Gradle 9.6.0 | `1244396` |
| 6 | Versioned distribution: XCFramework via SPM + Maven, CI, release `0.5.0` | `704f4cd` … `e106b4c` |

## Step 0 — native baseline
Android (Kotlin, Compose) and iOS (Swift, SwiftUI) implemented the same screens, strings and
business rules independently, with the same `Domain/` and `Data/` type names on both sides, so the
later extraction would be a move rather than a redesign.

## Step 1 — domain layer
`Task`, `Priority`, `ValidationError`, `TaskValidator`, `TaskFilter` (+ `StatusFilter`, `SortOrder`),
`StatsCalculator` (+ `TaskStats`), `DateProvider`, `DateFormatting` and the DTO → Task rule
(`TodoMapper`) moved to `commonMain`, with the tests ported to `commonTest`.
- `java.time.LocalDate` / Swift `Date` → `kotlinx.datetime.LocalDate`; `IosDates` (iosMain) converts
  to and from Swift `Date`.
- `Clock` → `DateProvider` (the name clashes with `kotlin.time.Clock` and Swift's `Clock`).
- `Task` is exported to Swift as `TaskItem` (`@ObjCName`) to avoid Swift Concurrency's `Task`.
- Integration at this stage: Android used a composite build (`includeBuild("../shared")`); iOS used
  direct integration (an Xcode Run Script calling `embedAndSignAppleFrameworkForXcode`).
- The automated Claude PR reviewer (`.claude/agents/swift-reviewer.md`,
  `.github/workflows/claude-review.yml`) was added alongside this step.

## Step 2 — data layer
`TaskApi` / `KtorTaskApi` (Ktor + kotlinx.serialization) replaced `HttpURLConnection` on Android and
`URLSession` + `Codable` on iOS; one internal `TodoDto` replaced two copies. `TaskRepository` became
the single source of truth with `state: StateFlow<LoadState>`. Without SKIE the iOS app needed a
small callback bridge to observe the `StateFlow`.

## Step 3 — SKIE
The SKIE plugin made the Kotlin API idiomatic in Swift: `suspend` → `async` with cancellation,
`StateFlow` → typed `AsyncSequence`, sealed `LoadState` → exhaustive `onEnum(of:)`, Kotlin enums →
Swift enums. The hand-written callback bridge was deleted.

## Step 4 — presentation layer
`TaskListViewModel`, `TaskDetailViewModel`, `TaskEditorViewModel` and `StatsViewModel` (with their
UiState types) moved to `commonMain`, built on the multiplatform `androidx.lifecycle.ViewModel`.
Android uses them as ordinary ViewModels; iOS wraps each in a generic Swift `SharedViewModel` that
mirrors `uiState` and calls the iosMain `clear()` on deinit. `taskId` became a constructor parameter
instead of `SavedStateHandle` (Android-only). ViewModel tests run once in `commonTest`.

## Step 5 — UI-only screens on the app side
No change to the shared API. Both apps split each screen into a stateless view and a platform
presentation layer on top of the shared ViewModel: Android uses `*Screen` ← `*UiModel` mappers ←
`*ScreenRoute`; iOS uses body-only SwiftUI views over per-screen Swift ViewModels (Combine
`ObservableObject`). `shared` and `android-app` were also bumped to AGP 9.4.1 and Gradle 9.6.0.

## Step 6 — versioned distribution
- The Ktor engine is selected explicitly with `expect`/`actual` `platformHttpEngine()`; load failures
  are logged.
- `XCFramework("Shared")` + root `Package.swift` (`binaryTarget(url:checksum:)`) for SPM; SKIE's
  `produceDistributableFramework()` enables library evolution so the binary works across Xcode versions.
- `maven-publish` to GitHub Packages as `com.example.tasks:shared`, version taken from the git tag.
- CI (`ci.yml`) and a manual release workflow (`release.yml`) that builds, checksums, commits
  `Package.swift`, tags, creates the GitHub Release and publishes to Maven.
- `0.5.0` released. The apps now consume versioned artifacts: Android from GitHub Packages (composite
  build kept behind `-PuseLocalShared=true`), iOS via SPM (the Gradle Run Script was removed).

## Possible next steps
Persistence (e.g. SQLDelight), shared DI (e.g. Koin) to replace the hand-written factories.
