/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */
// Main HTTP handlers and related data definitions.

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
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*

// GLOBALS
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank")
val db = Database(System.getProperty("BANK_DB_CONNECTION_STRING"))
// fixme: make enum out of error codes.
const val GENERIC_JSON_INVALID = 22
const val GENERIC_PARAMETER_MALFORMED = 26
const val GENERIC_PARAMETER_MISSING = 25
const val BANK_UNMANAGED_EXCEPTION = 5110
const val BANK_BAD_FORMAT_AMOUNT = 5108
const val GENERIC_HTTP_HEADERS_MALFORMED = 23
const val GENERIC_INTERNAL_INVARIANT_FAILURE = 60
const val BANK_LOGIN_FAILED = 5105
const val GENERIC_UNAUTHORIZED = 40
const val GENERIC_UNDEFINED = -1 // Filler for ECs that don't exist yet.

// TYPES

enum class FracDigits(howMany: Int) {
    TWO(2),
    EIGHT(8)
}

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

/**
 * This is the _internal_ representation of a RelativeTime
 * JSON type.
 */
data class RelativeTime(
    val d_us: Long
)

/**
 * This custom (de)serializer interprets the RelativeTime JSON
 * type.  In particular, it is responsible for converting the
 * "forever" string into Long.MAX_VALUE.  Any other numeric value
 * is passed as is.
 */
object RelativeTimeSerializer : KSerializer<RelativeTime> {
    override fun serialize(encoder: Encoder, value: RelativeTime) {
        throw internalServerError("Encoding of RelativeTime not implemented.") // API doesn't require this.
    }
    override fun deserialize(decoder: Decoder): RelativeTime {
        val jsonInput = decoder as? JsonDecoder ?: throw internalServerError("RelativeTime had no JsonDecoder")
        val json = try {
            jsonInput.decodeJsonElement().jsonObject
        } catch (e: Exception) {
            throw badRequest(e.message) // JSON was malformed.
        }
        val maybeDUs = json["d_us"]?.jsonPrimitive ?: throw badRequest("Relative time invalid: d_us field not found")
        if (maybeDUs.isString) {
            if (maybeDUs.content != "forever") throw badRequest("Only 'forever' allowed for d_us as string, but '${maybeDUs.content}' was found")
            return RelativeTime(d_us = Long.MAX_VALUE)
        }
        val dUs: Long = maybeDUs.longOrNull ?: throw badRequest("Could not convert d_us: '${maybeDUs.content}' to a number")
        return RelativeTime(d_us = dUs)
    }

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("RelativeTime") {
            element<JsonElement>("d_us")
        }
}
@Serializable
data class TokenRequest(
    val scope: TokenScope,
    @Contextual
    val duration: RelativeTime
)

class LibeufinBankException(
    val httpStatus: HttpStatusCode,
    val talerError: TalerError
) : Exception(talerError.hint)

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
        else -> throw LibeufinBankException(
            httpStatus = HttpStatusCode.Unauthorized,
            talerError = TalerError(
                code = GENERIC_UNAUTHORIZED,
                hint = "Authorization method wrong or not supported."
            )
        )
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
        /**
         * This branch triggers when the Ktor layers detect one
         * invalid request.  It _might_ be thrown by the bank's
         * actual logic, but that should be avoided because this
         * (Ktor native) type doesn't easily map to the Taler error
         * format.
         */
        exception<BadRequestException> {call, cause ->
            // Discouraged use, but the only helpful message:
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
                status = HttpStatusCode.BadRequest,
                message = TalerError(
                    code = talerErrorCode,
                    hint = rootCause?.message
                ))
        }
        /**
         * This branch triggers when a bank handler throws it, and namely
         * after one logical failure of the request(-handling).  This branch
         * should be preferred to catch errors, as it allows to include the
         * Taler specific error detail.
         */
        exception<LibeufinBankException> {call, cause ->
            logger.error(cause.talerError.hint)
            call.respond(
                status = cause.httpStatus,
                message = cause.talerError
            )
        }
        // Catch-all branch to mean that the bank wasn't able to manage one error.
        exception<Exception> {call, cause ->
            logger.error(cause.message)
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = TalerError(
                    code = BANK_UNMANAGED_EXCEPTION,// 5110, bank's fault
                    hint = cause.message
                )
            )
        }
    }
    routing {
        post("/accounts/{USERNAME}/auth-token") {
            val customer = call.myAuth(TokenScope.readwrite)
            val endpointOwner = call.expectUriComponent("USERNAME")
            if (customer == null || customer.login != endpointOwner)
                throw unauthorized("Auth failed or client has no rights")

        }
        post("/accounts") {
            // check if only admin.
            val maybeOnlyAdmin = db.configGet("only_admin_registrations")
            if (maybeOnlyAdmin?.lowercase() == "yes") {
                val customer: Customer? = call.myAuth(TokenScope.readwrite)
                if (customer == null || customer.login != "admin")
                    throw LibeufinBankException(
                        httpStatus = HttpStatusCode.Unauthorized,
                        talerError = TalerError(
                            code = BANK_LOGIN_FAILED,
                            hint = "Either 'admin' not authenticated or an ordinary user tried this operation."
                        )
                    )
            }
            // auth passed, proceed with activity.
            val req = call.receive<RegisterAccountRequest>()
            // Prohibit reserved usernames:
            if (req.username == "admin" || req.username == "bank")
                throw LibeufinBankException(
                    httpStatus = HttpStatusCode.Conflict,
                    talerError = TalerError(
                        code = GENERIC_UNDEFINED, // FIXME: this waits GANA.
                        hint = "Username '${req.username}' is reserved."
                    )
                )
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
                    CryptoUtil.checkpw(req.password, maybeCustomerExists.passwordHash) &&
                    maybeHasBankAccount.isPublic == req.is_public &&
                    maybeHasBankAccount.isTalerExchange == req.is_taler_exchange &&
                    maybeHasBankAccount.internalPaytoUri == req.internal_payto_uri
                if (isIdentic) {
                    call.respond(HttpStatusCode.Created)
                    return@post
                }
                throw LibeufinBankException(
                    httpStatus = HttpStatusCode.Conflict,
                    talerError = TalerError(
                        code = GENERIC_UNDEFINED, // GANA needs this.
                        hint = "Idempotency check failed."
                    )
                )
            }
            // From here: fresh user being added.
            val newCustomer = Customer(
                login = req.username,
                name = req.name,
                email = req.challenge_contact_data?.email,
                phone = req.challenge_contact_data?.phone,
                cashoutPayto = req.cashout_payto_uri,
                // Following could be gone, if included in cashout_payto_uri
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