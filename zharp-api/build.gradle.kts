plugins {
    id("zharp.java-conventions")
    id("zharp.publishing")
}

description = "Zharp for Java: the API (Zharp.captureError, setUser, setTag, log, startSpan, flush). Works through the Zharp agent or the Spring Boot starter; add io.zharp:zharp for library mode."

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
}
