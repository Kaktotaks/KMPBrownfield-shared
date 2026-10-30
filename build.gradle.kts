import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    // ⚡️ SKIE (Touchlab): компіляторний плагін, який поверх ObjC-заголовка генерує
    // Swift-обгортки в тому ж Shared.framework. Впливає ТІЛЬКИ на iOS-framework;
    // Android і Kotlin-код не змінюються. Що дає зі Swift:
    //   suspend fun     → async (з підтримкою скасування, можна викликати з будь-якого потоку)
    //   Flow/StateFlow  → AsyncSequence (for await), generic зберігається, .value типізоване
    //   sealed class    → onEnum(of:) → exhaustive switch
    //   enum class      → справжній Swift enum
    alias(libs.plugins.skie)
    // ⚡️ Публікація в Maven (GitHub Packages) для android-app. KMP-плагін сам створює
    // публікації: shared (root, Gradle module metadata) + shared-android + shared-iosarm64 + ...
    `maven-publish`
}

// Координати бібліотеки: com.example.tasks:shared:<version>.
// ⚡️ Джерело правди для версії — git-тег. Release workflow передає -PsharedVersion=0.5.0;
// локально (composite build / debug XCFramework) версія не важлива, тому "0.0.0-local".
group = "com.example.tasks"
version = providers.gradleProperty("sharedVersion").getOrElse("0.0.0-local")

kotlin {
    compilerOptions {
        // kotlin.time.Clock (через kotlinx-datetime todayIn) і @ObjCName поки під opt-in.
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.experimental.ExperimentalObjCName")
    }

    // Android: звичайна Kotlin/JVM-бібліотека (AAR) для android-app.
    android {
        namespace = "com.example.tasks.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    // iOS: Kotlin/Native → Shared.framework.
    // ⚡️ XCFramework("Shared") збирає ОДИН контейнер з усіма iOS-слайсами (device + simulator):
    //   ./gradlew assembleSharedReleaseXCFramework → build/XCFrameworks/release/Shared.xcframework
    // CI пакує його в zip, кладе в GitHub Release, а Package.swift посилається на цей zip.
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
            // api, а не implementation: ці типи є частиною публічного API
            // (Task.dueDate: LocalDate, TaskRepository.state: StateFlow),
            // тому android-app має бачити їх транзитивно.
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            // ⚡️ Google androidx ViewModel — мультиплатформний (є iosArm64/iosSimulatorArm64).
            // api: Android-застосунок використовує ці VM як звичайні androidx ViewModel.
            api(libs.androidx.lifecycle.viewmodel)

            // Мережа: Ktor + kotlinx.serialization. Деталь реалізації → implementation.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        // ⚡️ Engine — єдина платформна частина мережі: OkHttp на Android, NSURLSession (Darwin) на iOS.
        // HttpClient() сам знаходить engine, який лежить у залежностях конкретної платформи.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// ⚡️ SKIE + бінарна дистрибуція. XCFramework, зібраний на CI одним Xcode, підключається
// застосунком, який збирається ІНШИМ Xcode. Без library evolution Swift-частина SKIE
// (.swiftmodule) прив'язана до конкретної версії компілятора → "module compiled with Swift X
// cannot be imported by Swift Y". produceDistributableFramework() вмикає library evolution
// (.swiftinterface замість бінарного .swiftmodule) + relative paths у debug-символах.
skie {
    build {
        produceDistributableFramework()
    }
}

// ⚡️ Куди публікується Maven-артефакт. GitHub Packages прив'язаний до репозиторію.
// credentials(PasswordCredentials::class) читає Gradle-властивості
// GitHubPackagesUsername / GitHubPackagesPassword (на CI — через env
// ORG_GRADLE_PROJECT_GitHubPackagesUsername/Password), тож токен не потрапляє в код.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Kaktotaks/KMPBrownfield-shared")
            credentials(PasswordCredentials::class)
        }
    }
}
