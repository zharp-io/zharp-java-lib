// Spring Boot 3, WebFlux on Netty, Logback — run with the Zharp agent.
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation("io.zharp:zharp:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
