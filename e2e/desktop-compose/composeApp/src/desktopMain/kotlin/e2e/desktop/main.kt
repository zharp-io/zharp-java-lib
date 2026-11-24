package e2e.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.zharp.Zharp
import io.zharp.ZharpOptions
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The e2e app. E2E_ACTION picks what happens once the "Checkout" window is up:
 *   handled — Zharp.captureError of a NumberFormatException, then the "Settings" window, then quit
 *   crash   — an exception on the window's UI thread (Compose closes the window and rethrows on the event thread)
 *   thread  — an uncaught IllegalArgumentException on a worker thread, the app keeps running, then quit
 *   main    — an exception on the main thread before any window
 */
fun main() {
    Zharp.init(ZharpOptions.builder().desktop(true).build())
    val action = System.getenv("E2E_ACTION") ?: "handled"
    println("e2e desktop app: $action")
    if (action == "main") Cart(listOf("3")).checkout()

    application {
        var settings by remember { mutableStateOf(false) }
        Window(onCloseRequest = ::exitApplication, title = "Checkout") {
            MaterialTheme {
                Column {
                    Text("Cart")
                    Button(onClick = { Cart(listOf("3", "4")).checkout() }) { Text("Pay") }
                }
            }
            LaunchedEffect(Unit) {
                delay(1500)
                when (action) {
                    "handled" -> {
                        try {
                            Cart(listOf("12", "twelve")).total()
                        } catch (e: NumberFormatException) {
                            Zharp.captureError(e)
                        }
                        settings = true
                        delay(1500)
                        Zharp.flush(5000)
                        exitApplication()
                    }
                    // An effect on the window's UI thread throws, as a click handler would: Compose's
                    // window exception handler closes the window and rethrows on the event thread.
                    "crash" -> Cart(listOf("3", "4")).checkout()
                    "thread" -> {
                        Thread({ Cart(listOf("3")).applyCoupon("FREE") }, "coupon-sync").start()
                        delay(2500)
                        exitApplication()
                    }
                }
            }
        }
        if (settings) Window(onCloseRequest = { settings = false }, title = "Settings") { Text("Settings") }
    }
    exitProcess(0)
}
