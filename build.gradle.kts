import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

// Координати бібліотеки: com.example.tasks:shared:0.1.0.
// Зараз їх використовує composite build, пізніше — публікація в Maven.
group = "com.example.tasks"
version = "0.1.0"

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
            // api, а не implementation: LocalDate є частиною публічного API (Task.dueDate),
            // тому android-app має бачити kotlinx-datetime транзитивно.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
