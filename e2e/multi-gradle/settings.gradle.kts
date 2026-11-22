// Two modules: the app, and a library the error is thrown in. Zharp's Gradle plugin is applied to
// the app only; the library's jar gets the same build ID and its sources are uploaded too.
pluginManagement {
    repositories {
        maven { url = uri("../../build/e2e-repo") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/e2e-repo") }
        mavenCentral()
    }
}

rootProject.name = "multi-gradle"
include("app", "lib")
