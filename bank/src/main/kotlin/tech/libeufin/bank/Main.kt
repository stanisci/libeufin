package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.callloging.*
import kotlinx.serialization.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*

// GLOBALS
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank")
val db = Database(System.getProperty("BANK_DB_CONNECTION_STRING"))
const val GENERIC_JSON_INVALID = 22
const val GENERIC_PARAMETER_MALFORMED = 26
const val GENERIC_PARAMETER_MISSING = 25

// TYPES
@Serializable
data class TalerError(
    val code: Int,
    val hint: String? = null
)

@Serializable
data class ChallengeContactData(
    val email: String? = null,
    val phone: String? = null
)
@Serializable
data class RegisterAccountRequest(
    val username: String,
    val password: String,
    val name: String,
    val is_public: Boolean = false,
    val is_taler_exchange: Boolean = false,
    val challenge_contact_data: ChallengeContactData? = null,
    val cashout_payto_uri: String? = null,
    val internal_payto_uri: String? = null
)

// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"
fun parseTalerAmount(amount: String): TalerAmount {
    val amountWithCurrencyRe = "^([A-Z]+):([0-9]+(\\.[0-9][0-9]?)?)$"
    val match = Regex(amountWithCurrencyRe).find(amount) ?:
    throw badRequest("Invalid amount")
    val value = match.destructured.component2()
    val fraction: Int = match.destructured.component3().run {
        if (this.isEmpty()) return@run 0
        return@run this.substring(1).toInt()
    }
    return TalerAmount(value.toLong(), fraction)
}

/**
 * Performs the HTTP basic authentication.  Returns the
 * authenticated customer on success, or null otherwise.
 */
fun doBasicAuth(encodedCredentials: String): Customer? {
    val plainUserAndPass = String(base64ToBytes(encodedCredentials), Charsets.UTF_8) // :-separated
    val userAndPassSplit = plainUserAndPass.split(
        ":",
        /**
         * this parameter allows colons to occur in passwords.
         * Without this, passwords that have colons would be split
         * and become meaningless.
         */
        limit = 2
    )
    if (userAndPassSplit.size != 2) throw badRequest("Malformed Basic auth credentials found in the Authorization header.")
    val login = userAndPassSplit[0]
    val plainPassword = userAndPassSplit[1]
    return db.customerPwAuth(login, CryptoUtil.hashpw(plainPassword))
}

/* Performs the bearer-token authentication.  Returns the
 * authenticated customer on success, null otherwise. */
fun doTokenAuth(
    token: String,
    requiredScope: TokenScope, // readonly or readwrite
): Customer? {
    val maybeToken: BearerToken = db.bearerTokenGet(token.toByteArray(Charsets.UTF_8)) ?: return null
    val isExpired: Boolean = maybeToken.expirationTime - getNow().toMicro() < 0
    if (isExpired || maybeToken.scope != requiredScope) return null // FIXME: mention the reason?
    // Getting the related username.
    return db.customerGetFromRowId(maybeToken.bankCustomer)
        ?: throw internalServerError("Customer not found, despite token mentions it.")
}

/**
 * This function tries to authenticate the call according
 * to the scheme that is mentioned in the Authorization header.
 * The allowed schemes are either 'HTTP basic auth' or 'bearer token'.
 *
 * requiredScope can be either "readonly" or "readwrite".
 *
 * Returns the authenticated customer, or null if they failed.
 */
fun ApplicationCall.myAuth(requiredScope: TokenScope): Customer? {
    // Extracting the Authorization header.
    val header = getAuthorizationRawHeader(this.request)
    val authDetails = getAuthorizationDetails(header)
    return when (authDetails.scheme) {
        "Basic" -> doBasicAuth(authDetails.content)
        "Bearer" -> doTokenAuth(authDetails.content, requiredScope)
        else -> throw badRequest("Authorization scheme '${authDetails.scheme}' is not supported.")
    }
}

val webApp: Application.() -> Unit = {
    install(CallLogging) {
        this.level = Level.DEBUG
        this.logger = tech.libeufin.bank.logger
        this.format { call ->
            "${call.response.status()}, ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(IgnoreTrailingSlash)
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = false
        })
    }
    install(RequestValidation)
    install(StatusPages) {
        exception<BadRequestException> {call, cause ->
            // Discouraged use, but the only helpful message.
            var rootCause: Throwable? = cause.cause
            while (rootCause?.cause != null)
                rootCause = rootCause.cause
            logger.error(rootCause?.message)
            // Telling apart invalid JSON vs missing parameter vs invalid parameter.
            val talerErrorCode = when(cause) {
                is MissingRequestParameterException -> GENERIC_PARAMETER_MISSING // 25
                is ParameterConversionException -> GENERIC_PARAMETER_MALFORMED // 26
                else -> GENERIC_JSON_INVALID // 22
            }
            call.respond(
                HttpStatusCode.BadRequest,
                TalerError(
                    code = talerErrorCode,
                    hint = rootCause?.message
                ))
        }
    }
    routing {
        post("/accounts") {
            // check if only admin.
            val maybeOnlyAdmin = db.configGet("only_admin_registrations")
            if (maybeOnlyAdmin?.lowercase() == "yes") {
                val customer: Customer? = call.myAuth(TokenScope.readwrite)
                if (customer == null || customer.login != "admin")
                    // OK to leak the only-admin policy here?
                    throw unauthorized("Only admin allowed, and it failed to authenticate.")
            }
            // auth passed, proceed with activity.
            val req = call.receive<RegisterAccountRequest>()
            // Prohibit reserved usernames:
            if (req.username == "admin" || req.username == "bank")
                throw conflict("Username '${req.username}' is reserved.")
            // Checking imdepotency.
            val maybeCustomerExists = db.customerGetFromLogin(req.username)
            // Can be null if previous call crashed before completion.
            val maybeHasBankAccount = maybeCustomerExists.run {
                if (this == null) return@run null
                db.bankAccountGetFromOwnerId(this.expectRowId())
            }
            if (maybeCustomerExists != null && maybeHasBankAccount != null) {
                logger.debug("Registering username was found: ${maybeCustomerExists.login}")
                // Checking _all_ the details are the same.
                val isIdentic =
                    maybeCustomerExists.name == req.name &&
                    maybeCustomerExists.email == req.challenge_contact_data?.email &&
                    maybeCustomerExists.phone == req.challenge_contact_data?.phone &&
                    maybeCustomerExists.cashoutPayto == req.cashout_payto_uri &&
                    maybeCustomerExists.passwordHash == CryptoUtil.hashpw(req.password) &&
                    maybeHasBankAccount.isPublic == req.is_public &&
                    maybeHasBankAccount.isTalerExchange == req.is_taler_exchange &&
                    maybeHasBankAccount.internalPaytoUri == req.internal_payto_uri
                if (isIdentic) call.respond(HttpStatusCode.Created)
                call.respond(HttpStatusCode.Conflict)
            }
            // From here: fresh user being added.
            val newCustomer = Customer(
                login = req.username,
                name = req.name,
                email = req.challenge_contact_data?.email,
                phone = req.challenge_contact_data?.phone,
                cashoutPayto = req.cashout_payto_uri,
                // Following could be gone, if included in cashout_payto
                cashoutCurrency = db.configGet("cashout_currency"),
                passwordHash = CryptoUtil.hashpw(req.password)
            )
            val newCustomerRowId = db.customerCreate(newCustomer)
                ?: throw internalServerError("New customer INSERT failed despite the previous checks")
            /* Crashing here won't break data consistency between customers
             * and bank accounts, because of the idempotency.  Client will
             * just have to retry.  */
            val maxDebt = db.configGet("max_debt_ordinary_customers").run {
                if (this == null) throw internalServerError("Max debt not configured")
                parseTalerAmount(this)
            }
            val newBankAccount = BankAccount(
                hasDebt = false,
                internalPaytoUri = req.internal_payto_uri ?: genIbanPaytoUri(),
                owningCustomerId = newCustomerRowId,
                isPublic = req.is_public,
                isTalerExchange = req.is_taler_exchange,
                maxDebt = maxDebt
            )
            if (!db.bankAccountCreate(newBankAccount))
                throw internalServerError("Could not INSERT bank account despite all the checks.")
            call.respond(HttpStatusCode.Created)
            return@post
        }
    }
}