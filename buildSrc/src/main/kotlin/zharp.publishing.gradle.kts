import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

// Maven Central, as io.zharp:android is published: the same account, signing key and POM metadata.
// Credentials and the key are never in this repo — ~/.gradle/gradle.properties, or
// ORG_GRADLE_PROJECT_mavenCentralUsername / _mavenCentralPassword / _signingInMemoryKey /
// _signingInMemoryKeyId / _signingInMemoryKeyPassword in CI. Nothing here publishes on its own.
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signing.keyId").isPresent || providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "Zharp for Java" })
        inceptionYear.set("2026")
        url.set("https://github.com/zharp-io/zharp-java-lib")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zharp")
                name.set("Zharp")
                url.set("https://zharp.io")
            }
        }
        scm {
            url.set("https://github.com/zharp-io/zharp-java-lib")
            connection.set("scm:git:https://github.com/zharp-io/zharp-java-lib.git")
            developerConnection.set("scm:git:ssh://git@github.com/zharp-io/zharp-java-lib.git")
        }
    }
}

// The end-to-end apps (e2e/) build against what's here, from a repository on disk: never Central.
publishing {
    repositories {
        maven {
            name = "e2e"
            url = uri(rootProject.layout.buildDirectory.dir("e2e-repo"))
        }
    }
}
