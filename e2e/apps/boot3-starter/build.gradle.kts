// Spring Boot 3, MVC, JDBC on Postgres, Logback — with Zharp's Spring Boot starter and no agent.
// The same app as boot3-mvc (its sources are shared).
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

sourceSets.main {
    java.srcDir("../boot3-mvc/src/main/java")
    resources.srcDir("../boot3-mvc/src/main/resources")
}

dependencyManagement {
    // As OpenTelemetry's docs say for their starter: their BOM first, so Spring Boot's older
    // OpenTelemetry versions don't win.
    imports { mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.34.0") }
}

dependencies {
    implementation("io.zharp:zharp-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
}
