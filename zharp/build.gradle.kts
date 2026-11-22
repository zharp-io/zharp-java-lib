plugins {
    id("zharp.java-conventions")
    id("zharp.publishing")
}

description = "Zharp for Java: errors, traces and logs sent to Zharp. The API (Zharp.captureError, setUser, log, startSpan) and library mode for apps that run without the agent."

val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val v = project.version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("io/zharp/internal/Version.java").asFile
        file.parentFile.mkdirs()
        file.writeText("package io.zharp.internal;\n\n/** Generated at build time. */\npublic final class Version {\n    public static final String VERSION = \"$v\";\n\n    private Version() {}\n}\n")
    }
}
sourceSets.main { java.srcDir(generateVersion) }

dependencies {
    api(platform("io.opentelemetry:opentelemetry-bom:${Versions.OTEL_SDK}"))
    api("io.opentelemetry:opentelemetry-api")

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
