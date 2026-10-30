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

ViewModels stay native — next steps.

## Commands
```sh
./gradlew allTests                              # common tests on Android host + iOS simulator
./gradlew :linkDebugFrameworkIosSimulatorArm64  # build the iOS framework manually
```
