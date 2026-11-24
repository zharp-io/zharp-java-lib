import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("io.zharp.java") version "0.2.0"
}

kotlin {
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.zharp:zharp:0.2.0")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "e2e.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "zharp-desktop-e2e"
            packageVersion = "1.4.2"
        }
        // E2E_OBFUSCATE=true: a release build with ProGuard obfuscation (default: off, as Compose ships it).
        buildTypes.release.proguard {
            obfuscate.set(System.getenv("E2E_OBFUSCATE") == "true")
            configurationFiles.from(project.file("rules.pro"))
        }
    }
}
