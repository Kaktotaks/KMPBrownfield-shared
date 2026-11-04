import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    // SKIE (Touchlab): compiler plugin that generates Swift wrappers on top of the ObjC header,
    // inside the same Shared framework. Affects the iOS framework only; Android and Kotlin code
    // are unchanged. What Swift gets:
    //   suspend fun     → async (cancellable, callable from any thread)
    //   Flow/StateFlow  → typed AsyncSequence (for await), typed .value
    //   sealed class    → onEnum(of:) → exhaustive switch
    //   enum class      → real Swift enum
    alias(libs.plugins.skie)
    // SQLDelight: generates the typed TasksDatabase API from src/commonMain/sqldelight/**.sq.
    alias(libs.plugins.sqldelight)
    // Maven publishing (GitHub Packages) for android-app. The KMP plugin creates the publications:
    // shared (root, Gradle module metadata) + shared-android + shared-iosarm64 + ...
    `maven-publish`
}

// Coordinates: com.example.tasks:shared:<version>.
// The git tag is the source of truth for the version: release.yml passes -PsharedVersion=X.Y.Z.
// Locally (composite build / debug XCFramework) the version doesn't matter, hence "0.0.0-local".
group = "com.example.tasks"
version = providers.gradleProperty("sharedVersion").getOrElse("0.0.0-local")

kotlin {
    compilerOptions {
        // kotlin.time.Clock (used via kotlinx-datetime todayIn) and @ObjCName still require opt-in.
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.experimental.ExperimentalObjCName")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        // expect/actual classes (DatabaseFactory) are still flagged as Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Android: a regular Android library (AAR) for android-app.
    android {
        namespace = "com.example.tasks.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    // iOS: Kotlin/Native → static Shared.framework per slice.
    // XCFramework("Shared") bundles all iOS slices (device + simulator) into one container:
    //   ./gradlew assembleSharedReleaseXCFramework → build/XCFrameworks/release/Shared.xcframework
    // CI zips it into a GitHub Release; Package.swift points at that zip.
    val xcFramework = XCFramework("Shared")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: these types are part of the public API
            // (Task.dueDate: LocalDate, TaskRepository.state: StateFlow),
            // so android-app must see them transitively.
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            // androidx ViewModel is multiplatform (has iosArm64/iosSimulatorArm64 variants).
            // api: android-app uses the shared VMs as regular androidx ViewModels.
            api(libs.androidx.lifecycle.viewmodel)

            // Persistence. The database type (TasksDatabase) is part of the public constructor of
            // TaskRepository, so the runtime is api; coroutines-extensions is internal.
            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // Voice-note files (SystemFileSystem). Only Strings cross the public API → implementation.
            implementation(libs.kotlinx.io.core)

            // Networking: Ktor + kotlinx.serialization. Implementation detail → implementation.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        // The engine is the only platform-specific part of networking: OkHttp on Android,
        // Darwin (NSURLSession) on iOS. It is picked explicitly via expect/actual
        // platformHttpEngine() (data/HttpEngine.*.kt), not by classpath discovery.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // Android host tests run on the JVM: SQLite through JDBC (in-memory).
        // The iOS tests use the native driver (already on the iosMain classpath).
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("TasksDatabase") {
            packageName.set("com.example.tasks.shared.db")
            // Migration safety net: every N.sqm must turn schema N.db into the current schema.
            // Committed snapshots live in src/commonMain/sqldelight/databases (1.db = schema v1).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// SKIE + binary distribution: the XCFramework is built on CI with one Xcode and consumed by an app
// built with another. Without library evolution SKIE's Swift part (.swiftmodule) is tied to one
// compiler version ("module compiled with Swift X cannot be imported by Swift Y").
// produceDistributableFramework() enables library evolution (.swiftinterface instead of a binary
// .swiftmodule) and relative paths in debug symbols.
skie {
    build {
        produceDistributableFramework()
    }
}

// Maven repository: GitHub Packages of this GitHub repo.
// credentials(PasswordCredentials::class) reads the Gradle properties
// GitHubPackagesUsername / GitHubPackagesPassword (on CI via the env vars
// ORG_GRADLE_PROJECT_GitHubPackagesUsername/Password), so no token lives in the code.
publishing {
    // POM metadata only (shown on GitHub Packages / used by tooling); no effect on the build.
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("KMPBrownfield shared")
            description.set("Kotlin Multiplatform domain, data and presentation layers shared by the Tasks Android and iOS apps.")
            url.set("https://github.com/Kaktotaks/KMPBrownfield-shared")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("Kaktotaks")
                    name.set("Leonid Shevchenko")
                }
            }
            scm {
                url.set("https://github.com/Kaktotaks/KMPBrownfield-shared")
                connection.set("scm:git:https://github.com/Kaktotaks/KMPBrownfield-shared.git")
                developerConnection.set("scm:git:ssh://git@github.com/Kaktotaks/KMPBrownfield-shared.git")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Kaktotaks/KMPBrownfield-shared")
            credentials(PasswordCredentials::class)
        }
    }
}
