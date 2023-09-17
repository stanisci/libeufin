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
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import java.time.Duration
import kotlin.random.Random

// GLOBALS
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank")
val db = Database(System.getProperty("BANK_DB_CONNECTION_STRING"))
const val GENERIC_UNDEFINED = -1 // Filler for ECs that don't exist yet.
val TOKEN_DEFAULT_DURATION_US = Duration.ofDays(1L).seconds * 1000000


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
            throw badRequest(
                "Did not find a RelativeTime JSON object: ${e.message}",
                TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
            )
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
    val header = getAuthorizationRawHeader(this.request) ?: throw badRequest(
        "Authorization header not found.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val authDetails = getAuthorizationDetails(header) ?: throw badRequest(
        "Authorization is invalid.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    return when (authDetails.scheme) {
        "Basic" -> doBasicAuth(authDetails.content)
        "Bearer" -> doTokenAuth(authDetails.content, requiredScope)
        else -> throw LibeufinBankException(
            httpStatus = HttpStatusCode.Unauthorized,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_UNAUTHORIZED.code,
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
            // Registering custom parser for RelativeTime
            serializersModule = SerializersModule {
                contextual(RelativeTime::class) {
                    RelativeTimeSerializer
                }
            }
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
                is MissingRequestParameterException ->
                    TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MISSING
                is ParameterConversionException ->
                    TalerErrorCode.TALER_EC_GENERIC_PARAMETER_MALFORMED
                else -> TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
            }
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = TalerError(
                    code = talerErrorCode.code,
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
                    code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
                    hint = cause.message
                )
            )
        }
    }
    routing {
        post("/accounts/{USERNAME}/token") {
            val customer = call.myAuth(TokenScope.refreshable) ?: throw unauthorized("Authentication failed")
            val endpointOwner = call.expectUriComponent("USERNAME")
            if (customer.login != endpointOwner)
                throw forbidden(
                    "User has no rights on this enpoint",
                    TalerErrorCode.TALER_EC_END // FIXME: need generic forbidden
                )
            val maybeAuthToken = call.getAuthToken()
            val req = call.receive<TokenRequest>()
            /**
             * This block checks permissions ONLY IF the call was authenticated
             * with a token.  Basic auth gets always granted.
             */
            if (maybeAuthToken != null) {
                val tokenBytes = Base32Crockford.decode(maybeAuthToken)
                val refreshingToken = db.bearerTokenGet(tokenBytes) ?: throw internalServerError(
                    "Token used to auth not found in the database!"
                )
                if (refreshingToken.scope == TokenScope.readonly && req.scope == TokenScope.readwrite)
                    throw forbidden(
                        "Cannot generate RW token from RO",
                        TalerErrorCode.TALER_EC_GENERIC_TOKEN_PERMISSION_INSUFFICIENT
                    )
            }
            val tokenBytes = ByteArray(32).apply {
                java.util.Random().nextBytes(this)
            }
            val maxDurationTime: Long = db.configGet("token_max_duration").run {
                if (this == null)
                    return@run Long.MAX_VALUE
                return@run try {
                    this.toLong()
                } catch (e: Exception) {
                    logger.error("Could not convert config's token_max_duration to Long")
                    throw internalServerError(e.message)
                }
            }
            if (req.duration != null && req.duration.d_us.compareTo(maxDurationTime) == 1)
                throw forbidden(
                    "Token duration bigger than bank's limit",
                    // FIXME: define new EC for this case.
                    TalerErrorCode.TALER_EC_END
                )
            val tokenDurationUs  = req.duration?.d_us ?: TOKEN_DEFAULT_DURATION_US
            val customerDbRow = customer.dbRowId ?: throw internalServerError(
                "Coud not resort customer '${customer.login}' database row ID"
            )
            val expirationTimestampUs: Long = getNowUs() + tokenDurationUs
            if (expirationTimestampUs < tokenDurationUs)
                throw badRequest(
                    "Token duration caused arithmetic overflow",
                    // FIXME: need dedicate EC (?)
                    talerErrorCode = TalerErrorCode.TALER_EC_END
                )
            val token = BearerToken(
                bankCustomer = customerDbRow,
                content = tokenBytes,
                creationTime = expirationTimestampUs,
                expirationTime = expirationTimestampUs,
                scope = req.scope,
                isRefreshable = req.refreshable
            )
            if (!db.bearerTokenCreate(token))
                throw internalServerError("Failed at inserting new token in the database")
            call.respond(TokenSuccessResponse(
                access_token = Base32Crockford.encode(tokenBytes),
                expiration = Timestamp(
                    t_s = expirationTimestampUs / 1000000L
                )
            ))
            return@post
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
                            code = TalerErrorCode.TALER_EC_BANK_LOGIN_FAILED.code,
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