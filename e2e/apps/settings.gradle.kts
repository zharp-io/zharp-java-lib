// The end-to-end apps. They use Zharp's artifacts from ../../build/e2e-repo (./gradlew
// publishAllPublicationsToE2eRepository at the repository root), never from Maven Central.
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

rootProject.name = "zharp-e2e"

include("boot3-mvc")
include("boot3-starter")
include("boot3-webflux")
include("servlet-jetty")
include("ktor")
include("library-mode")
