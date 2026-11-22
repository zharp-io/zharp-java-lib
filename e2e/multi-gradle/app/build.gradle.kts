plugins {
    java
    id("io.zharp.java") version "0.1.0"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { implementation(project(":lib")) }
