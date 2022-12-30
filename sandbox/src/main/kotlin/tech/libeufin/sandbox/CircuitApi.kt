package tech.libeufin.sandbox

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.InvalidPaytoError
import tech.libeufin.util.conflict
import tech.libeufin.util.parsePayto

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

// User registration request
class CircuitAccountRequest(
    val username: String,
    val password: String,
    val contact_data: CircuitAccountData,
    val name: String,
    val cashout_address: String, // payto
    val internal_iban: String? // Shall be "= null" ?
)
// User contact data to send the TAN.
class CircuitAccountData(
    val email: String?,
    val phone: String?
)

/**
 * Allows only the administrator to add new accounts.
 */
fun circuitApi(circuitRoute: Route) {
    circuitRoute.post("/accounts") {
        call.request.basicAuth(onlyAdmin = true)
        val req = call.receive<CircuitAccountRequest>()
        // Validity and availability check on the input data.
        if (req.contact_data.email != null) {
            val maybeEmailConflict = DemobankCustomerEntity.find {
                DemobankCustomersTable.email eq req.contact_data.email
            }.firstOrNull()
            if (maybeEmailConflict != null) {
                // Warning since two individuals claimed one same e-mail address.
                logger.warn("Won't register user ${req.username}: e-mail conflict on ${req.contact_data.email}")
                throw conflict("E-mail address already in use!")
            }
            // Syntactic validation.  Warn on error, since UI could avoid this.
            // FIXME
            // From Taler TypeScript:
            // /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/;
        }
        if (req.contact_data.phone != null) {
            val maybePhoneConflict = DemobankCustomerEntity.find {
                DemobankCustomersTable.phone eq req.contact_data.phone
            }.firstOrNull()
            if (maybePhoneConflict != null) {
                // Warning since two individuals claimed one same phone number.
                logger.warn("Won't register user ${req.username}: phone conflict on ${req.contact_data.email}")
                throw conflict("Phone number already in use!")
            }
            // Syntactic validation.  Warn on error, since UI could avoid this.
            // FIXME
            // From Taler TypeScript
            // /^\+[0-9 ]*$/;
        }
        // Check that cash-out address parses.
        try {
            parsePayto(req.cashout_address)
        } catch (e: InvalidPaytoError) {
            // Warning because the UI could avoid this.
            logger.warn("Won't register account ${req.username}: invalid cash-out address: ${req.cashout_address}")
        }
        transaction {
            val newAccount = insertNewAccount(
                username = req.username,
                password = req.password,
                name = req.name
            )
            newAccount.customer.phone = req.contact_data.phone
            newAccount.customer.email = req.contact_data.email
        }
        call.respond(HttpStatusCode.NoContent)
        return@post
    }
    circuitRoute.get("/config") {
        call.respond(ConfigResp(ratios_and_fees = RatioAndFees()))
        return@get
    }
}