package e2e.ktor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.zharp.Zharp
import io.zharp.ZharpUser
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("e2e.ktor.KtorApp")

/** The e2e routes in Kotlin on Ktor. */
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 18086
    embeddedServer(Netty, port = port) {
        routing {
            get("/hello") {
                log.info("hello from the e2e app")
                call.respondText("hi")
            }
            get("/users/{id}") {
                val id = call.parameters["id"]!!
                Zharp.setUser(ZharpUser.of(id).withEmail("$id@example.com"))
                call.respondText("""{"id":"$id"}""")
            }
            get("/boom") {
                throw IllegalStateException("boom from the controller")
            }
            get("/handled") {
                try {
                    "not a number".toInt()
                } catch (e: NumberFormatException) {
                    Zharp.captureError(e)
                }
                call.respondText("handled")
            }
            get("/logged") {
                try {
                    val value: Any? = null
                    value!!.toString()
                } catch (e: NullPointerException) {
                    log.error("could not read the value", e)
                }
                call.respondText("logged")
            }
            get("/thread-crash") {
                thread(name = "e2e-worker") { throw UnsupportedOperationException("crash in a worker thread") }
                call.respondText("started")
            }
            get("/exit") {
                thread {
                    Thread.sleep(300)
                    exitProcess(0)
                }
                call.respondText("bye")
            }
        }
    }.start(wait = true)
}
