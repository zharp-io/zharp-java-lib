// A Compose Multiplatform desktop app laid out as JetBrains' wizard makes it (composeApp, a KMP
// module with a desktop JVM target), using Zharp's artifacts from ../../build/e2e-repo.
pluginManagement {
    repositories {
        maven { url = uri("../../build/e2e-repo") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/e2e-repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "desktop-compose"
include(":composeApp")
