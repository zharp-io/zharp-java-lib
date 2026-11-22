plugins {
    id("zharp.java-conventions")
    id("zharp.publishing")
}

description = "Zharp for Spring Boot without the agent: OpenTelemetry's Spring Boot starter configured for Zharp by zharp.* properties."

dependencies {
    api(project(":zharp"))
    api(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:${Versions.OTEL_INSTRUMENTATION}"))
    api("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter")
    // Spring Boot 2.7's API: the starter runs on Spring Boot 2.7 and 3.x.
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:2.7.18")
    compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
    compileOnly("ch.qos.logback:logback-classic:1.2.13")
}
