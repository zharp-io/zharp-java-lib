// Library mode: no agent, no Spring — Zharp.init() in main, a CLI-style job, Logback with
// OpenTelemetry's Logback appender (so its records go to Zharp when ZHARP_LOGS=true).
plugins {
    application
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

application { mainClass.set("e2e.library.LibraryApp") }

dependencies {
    implementation("io.zharp:zharp:0.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.34.0-alpha")
}
