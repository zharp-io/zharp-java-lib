// Every module: Java 8 bytecode (like OpenTelemetry's agent), built and tested on the JDK running Gradle.
plugins {
    `java-library`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("ZHARP_JAVA_VERSION").get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        // The JDK that runs the build (21 locally and in CI); bytecode is pinned by `release` below.
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("zharp.jdk").getOrElse("21").toInt()))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
    options.encoding = "UTF-8"
    // --release 8 is deprecated on newer JDKs; the warning says nothing we don't know.
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:${Versions.JUNIT}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
