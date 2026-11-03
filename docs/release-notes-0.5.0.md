First public release of the KMP core for the Tasks apps: domain, data and presentation layers
shared by the [Android](https://github.com/Kaktotaks/KMPBrownfield-android) and
[iOS](https://github.com/Kaktotaks/KMPBrownfield-ios) apps.

## What's in 0.5.0
- **Domain:** `Task` (Swift: `TaskItem`), `Priority`, `TaskValidator` + `ValidationError`,
  `TaskFilter` (`StatusFilter`, `SortOrder`), `StatsCalculator` + `TaskStats`, `DateProvider` /
  `SystemDateProvider`, `TodoMapper`, `DateFormatting`; `IosDates` on iOS.
- **Data:** `TaskApi` / `KtorTaskApi` (Ktor 3.6.1, kotlinx.serialization; OkHttp engine on Android,
  Darwin on iOS via `expect`/`actual`) and `TaskRepository` with `state: StateFlow<LoadState>`.
- **Presentation:** `TaskListViewModel`, `TaskDetailViewModel`, `TaskEditorViewModel`,
  `StatsViewModel` on the multiplatform `androidx.lifecycle.ViewModel` (2.11.0), each with a
  `uiState: StateFlow` and event methods. iOS: call `clear()` when the owning Swift object goes away.
- **Swift API via SKIE 0.10.16:** `suspend` → `async`, `StateFlow` → typed `AsyncSequence`, sealed
  types → `onEnum(of:)`, enums → Swift enums. Built with library evolution, so the binary works with
  a different Xcode than the one used on CI.
- Tests: 34 `commonTest` tests, run on the Android host and the iOS simulator.

Built with Kotlin 2.4.21, AGP 9.4.1, Gradle 9.6.0. iOS 15+ (package), Android minSdk 24.

## How to consume
**iOS (Swift Package Manager)** — `Shared.xcframework.zip` attached to this release:
Xcode → File → Add Package Dependencies → `https://github.com/Kaktotaks/KMPBrownfield-shared`,
product `Shared`, *Up to Next Minor* from `0.5.0`. Then `import Shared`.

**Android (Gradle, GitHub Packages)** — `com.example.tasks:shared:0.5.0`:
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Kaktotaks/KMPBrownfield-shared")
            credentials(PasswordCredentials::class) // GitHubPackagesUsername / GitHubPackagesPassword
            content { includeGroup("com.example.tasks") }
        }
    }
}
// app/build.gradle.kts
dependencies { implementation("com.example.tasks:shared:0.5.0") }
```
GitHub Packages needs a classic PAT with `read:packages` even for public packages
(`~/.gradle/gradle.properties`). Without a token, build the Android app from sources with
`-PuseLocalShared=true` (see the Android README).

See the [README](https://github.com/Kaktotaks/KMPBrownfield-shared#readme) and the
[migration log](https://github.com/Kaktotaks/KMPBrownfield-shared/blob/main/docs/MIGRATION.md).
