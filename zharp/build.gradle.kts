plugins {
    id("zharp.java-conventions")
    id("zharp.publishing")
}

description = "Zharp for Java without the agent (library mode): io.zharp:zharp-api plus OpenTelemetry's SDK and OTLP exporter, started by Zharp.init()."



dependencies {
    api(platform("io.opentelemetry:opentelemetry-bom:${Versions.OTEL_SDK}"))
    api(project(":zharp-api"))

    // Library mode: the SDK, configured the same way as the agent, exporting OTLP over HTTP.
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Used only where present: the instrumentation API (the agent and the Spring Boot starter
    // bring it) for request/response headers, and Logback for stamping log lines.
    compileOnly(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${Versions.OTEL_INSTRUMENTATION_ALPHA}"))
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-incubator")
    compileOnly("ch.qos.logback:logback-classic:1.2.13")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation("ch.qos.logback:logback-classic:1.3.15")
}
