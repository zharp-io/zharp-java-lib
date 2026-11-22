plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.gradleup.shadow:shadow-gradle-plugin:9.6.1")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.34.0")
}
