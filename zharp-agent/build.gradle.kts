import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// io.zharp:zharp-agent — OpenTelemetry's Java agent with Zharp's extension inside, as one jar, built
// the way OpenTelemetry's "distro" example builds one:
//   1. the extension (and the Zharp classes it uses) is relocated like the agent's own classes,
//   2. moved into inst/ as .classdata (the agent's isolated class loader),
//   3. merged with the upstream agent jar.
plugins {
    id("zharp.java-conventions")
    id("com.gradleup.shadow")
    id("zharp.publishing")
}

description = "Zharp's Java agent: OpenTelemetry's Java agent configured for Zharp. java -javaagent:zharp-agent.jar -jar app.jar"

val javaagentLibs: Configuration = configurations.create("javaagentLibs") { isCanBeConsumed = false }
val upstreamAgent: Configuration = configurations.create("upstreamAgent") { isCanBeConsumed = false }

dependencies {
    javaagentLibs(project(":zharp-agent-extension"))
    upstreamAgent("io.opentelemetry.javaagent:opentelemetry-javaagent:${Versions.OTEL_INSTRUMENTATION}")
}

fun isolateClasses(jars: Iterable<File>): CopySpec = copySpec {
    jars.forEach {
        from(zipTree(it)) {
            into("inst")
            rename("""^(.*)[.]class$""", "$1.classdata")
            exclude("^LICENSE\$", "META-INF/INDEX.LIST", "META-INF/*.DSA", "META-INF/*.SF", "META-INF/maven/**", "META-INF/MANIFEST.MF")
        }
    }
}

val relocateJavaagentLibs = tasks.register<ShadowJar>("relocateJavaagentLibs") {
    configurations = listOf(javaagentLibs)
    archiveFileName.set("javaagentLibs-relocated.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    mergeServiceFiles()
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    exclude("**/module-info.class")
    relocatePackages(this)
}

val isolateJavaagentLibs = tasks.register<Copy>("isolateJavaagentLibs") {
    dependsOn(relocateJavaagentLibs)
    with(isolateClasses(relocateJavaagentLibs.get().outputs.files))
    into(layout.buildDirectory.dir("isolated/javaagentLibs"))
}


tasks.shadowJar {
    configurations = listOf(upstreamAgent)
    from(isolateJavaagentLibs)
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles("inst/META-INF/services")
    filesMatching("inst/META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    exclude("**/module-info.class")
    relocatePackages(this)
}

// The published jar (what Maven Central serves as io.zharp:zharp-agent) is the merged agent as it is.
tasks.jar {
    dependsOn(tasks.shadowJar)
    from(zipTree(tasks.shadowJar.flatMap { it.archiveFile }))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
        attributes["Agent-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
        attributes["Premain-Class"] = "io.opentelemetry.javaagent.OpenTelemetryAgent"
        attributes["Can-Redefine-Classes"] = "true"
        attributes["Can-Retransform-Classes"] = "true"
        attributes["Implementation-Vendor"] = "Zharp"
        attributes["Implementation-Title"] = "zharp-agent"
        attributes["Implementation-Version"] = "${project.version}-otel-${Versions.OTEL_INSTRUMENTATION}"
    }
}

// A stable name for GitHub release assets and Dockerfiles: .../releases/latest/download/zharp-agent.jar
val stableAgentJar = tasks.register<Copy>("stableAgentJar") {
    from(tasks.jar)
    into(layout.buildDirectory.dir("release"))
    rename { "zharp-agent.jar" }
}

tasks.assemble { dependsOn(tasks.shadowJar, stableAgentJar) }

// Only the merged agent is published: not the shadow plugin's own "-all" variant of it.
(components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
