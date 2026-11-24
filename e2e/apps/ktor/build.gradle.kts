// Kotlin + Ktor 3 on Netty, Logback — run with the Zharp agent.
plugins {
    kotlin("jvm") version "2.2.21"
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("e2e.ktor.KtorAppKt") }

dependencies {
    implementation("io.zharp:zharp-api:0.2.0")
    implementation("io.ktor:ktor-server-netty:3.3.3")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}
