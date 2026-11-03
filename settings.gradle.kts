// Standalone Gradle build. The root project name is the artifactId, so Android consumes it as
// "com.example.tasks:shared" (see group in build.gradle.kts), and a composite build
// (includeBuild("../shared")) substitutes those coordinates with the sources.
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
