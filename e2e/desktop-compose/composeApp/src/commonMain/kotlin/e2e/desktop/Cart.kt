package e2e.desktop

/** Shared code (commonMain): where the e2e app's errors are thrown, so the stack points here. */
class Cart(private val prices: List<String>) {
    fun total(): Int = prices.sumOf { price ->
        price.toIntOrNull() ?: throw NumberFormatException("Price is not a number: \"$price\"")
    }

    fun applyCoupon(code: String): Int {
        require(code.startsWith("SAVE")) { "Unknown coupon: $code" }
        return code.removePrefix("SAVE").toInt()
    }

    fun checkout(): Nothing {
        throw IllegalStateException("Payment provider rejected the cart (${prices.size} items)")
    }
}
