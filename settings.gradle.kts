// ⚡️ Окремий самостійний Gradle-білд. Ім'я root-проєкту = artifactId,
// тому Android підключає його як "com.example.tasks:shared" (див. group у build.gradle.kts),
// а composite build (includeBuild("../shared")) підміняє ці координати на вихідний код.
rootProject.name = "shared"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}
