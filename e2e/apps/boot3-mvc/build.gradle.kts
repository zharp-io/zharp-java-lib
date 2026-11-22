// Spring Boot 3, MVC, JDBC on Postgres, Logback — run with the Zharp agent. Built with Zharp's
// Gradle plugin: the build ID in the jar, the sources uploaded (ZHARP_AUTH_TOKEN, ZHARP_APP_ID).
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.zharp.java") version "0.1.0"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation("io.zharp:zharp-api:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
}
