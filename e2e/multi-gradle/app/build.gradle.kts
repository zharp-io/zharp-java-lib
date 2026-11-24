plugins {
    java
    id("io.zharp.java") version "0.2.0"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { implementation(project(":lib")) }
