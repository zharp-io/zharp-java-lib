// Zharp's extension to OpenTelemetry's Java agent: compiled into zharp-agent.jar, never published alone.
plugins {
    id("zharp.java-conventions")
}

dependencies {
    // Bundled into the agent with the extension (the agent already has OpenTelemetry's SDK).
    implementation(project(":zharp")) { isTransitive = false }

    compileOnly(platform("io.opentelemetry:opentelemetry-bom:${Versions.OTEL_SDK}"))
    compileOnly(platform("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha:${Versions.OTEL_INSTRUMENTATION_ALPHA}"))
    compileOnly("io.opentelemetry:opentelemetry-sdk")
    compileOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")
    compileOnly("io.opentelemetry:opentelemetry-exporter-otlp")
    compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
    compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-incubator")
    compileOnly("net.bytebuddy:byte-buddy:1.17.7")
    compileOnly("ch.qos.logback:logback-classic:1.2.13")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
}
