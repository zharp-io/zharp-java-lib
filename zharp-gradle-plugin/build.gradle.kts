import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    id("zharp.java-conventions")
    `java-gradle-plugin`
    id("zharp.publishing")
}

description = "Zharp for Java builds: tags each build with an ID and uploads the app's sources, so errors show the code that ran."

sourceSets.main { java.srcDir("../build-support/src/main/java") }

dependencies {
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("zharpJava") {
            id = "io.zharp.java"
            implementationClass = "io.zharp.gradle.ZharpJavaPlugin"
            displayName = "Zharp for Java"
            description = project.description
        }
    }
}

// Published to Maven Central like the Android plugin: the implementation as
// io.zharp:zharp-java-gradle-plugin, and the marker io.zharp.java:io.zharp.java.gradle.plugin.
mavenPublishing {
    coordinates(project.group.toString(), "zharp-java-gradle-plugin", project.version.toString())
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}
