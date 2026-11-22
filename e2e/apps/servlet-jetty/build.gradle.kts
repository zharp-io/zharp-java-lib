// A plain Java servlet app: embedded Jetty 12 (Jakarta Servlet 6), JDBC on Postgres, Log4j 2 —
// no framework. Run with the Zharp agent.
plugins {
    application
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

application { mainClass.set("e2e.servlet.ServletApp") }

dependencies {
    implementation("io.zharp:zharp-api:0.1.0")
    implementation("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.0.41")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.2")
}
