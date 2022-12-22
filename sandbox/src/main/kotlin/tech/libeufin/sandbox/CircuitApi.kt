package tech.libeufin.sandbox

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*

// CIRCUIT API TYPES

// Configuration response:
class ConfigResp(
    val name: String = "circuit",
    val version: String = SANDBOX_VERSION,
    val ratios_and_fees: RatioAndFees
)

// After fixing #7527, the values held by this
// type must be read from the configuration.
class RatioAndFees(
    val buy_at_ratio: Float = 1F,
    val sell_at_ratio: Float = 0.05F,
    val buy_in_fee: Float = 0F,
    val sell_out_fee: Float = 0F
)

fun circuitApi(circuitRoute: Route) {
    circuitRoute.get("/config") {
        call.respond(ConfigResp(
            ratios_and_fees = RatioAndFees()
        ))
        return@get
    }
}