import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

/**
 * The relocations OpenTelemetry's agent applies to its own classes, applied to Zharp's extension
 * the same way (from the official "distro" example): the API, context and instrumentation API live
 * in the bootstrap class loader under javaagent.shaded names.
 */
fun relocatePackages(shadowJar: ShadowJar) {
    shadowJar.relocate("java.util.logging.Logger", "io.opentelemetry.javaagent.bootstrap.PatchLogger")
    shadowJar.relocate("io.opentelemetry.instrumentation", "io.opentelemetry.javaagent.shaded.instrumentation") {
        exclude("io.opentelemetry.instrumentation.resources.*")
        exclude("io.opentelemetry.instrumentation.spring.resources.*")
    }
    shadowJar.relocate("io.opentelemetry.api", "io.opentelemetry.javaagent.shaded.io.opentelemetry.api")
    shadowJar.relocate("io.opentelemetry.semconv", "io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv")
    shadowJar.relocate("io.opentelemetry.context", "io.opentelemetry.javaagent.shaded.io.opentelemetry.context")
    shadowJar.relocate("io.opentelemetry.common", "io.opentelemetry.javaagent.shaded.io.opentelemetry.common")
    shadowJar.relocate("io.opentelemetry.contrib.awsxray", "io.opentelemetry.javaagent.shaded.io.opentelemetry.contrib.awsxray")
    shadowJar.relocate("io.opentelemetry.extension.kotlin", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.kotlin")
}
