import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Координати бібліотеки: com.example.tasks:shared:0.2.0.
// Зараз їх використовує composite build, пізніше — публікація в Maven.
group = "com.example.tasks"
version = "0.2.0"

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

    // iOS: Kotlin/Native → Shared.framework для ios-app (direct integration через embedAndSign).
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: ці типи є частиною публічного API
            // (Task.dueDate: LocalDate, TaskRepository.state: StateFlow),
            // тому android-app має бачити їх транзитивно.
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)

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
