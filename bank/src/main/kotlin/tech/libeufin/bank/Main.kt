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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.*
import java.time.Duration

// GLOBALS
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank")
val db = Database(System.getProperty("BANK_DB_CONNECTION_STRING"))
const val GENERIC_UNDEFINED = -1 // Filler for ECs that don't exist yet.
val TOKEN_DEFAULT_DURATION_US = Duration.ofDays(1L).seconds * 1000000
const val FRACTION_BASE = 100000000


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
            prettyPrint = true
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
            /**
             * NOTE: extracting the root cause helps with JSON error messages,
             * because they mention the particular way they are invalid, but OTOH
             * it loses (by getting null) other error messages, like for example
             * the one from MissingRequestParameterException.  Therefore, in order
             * to get the most detailed message, we must consider BOTH sides:
             * the 'cause' AND its root cause!
             */
            var rootCause: Throwable? = cause.cause
            while (rootCause?.cause != null)
                rootCause = rootCause.cause
            /* Here getting _some_ error message, by giving precedence
             * to the root cause, as otherwise JSON details would be lost. */
            val errorMessage: String? = rootCause?.message ?: cause.message
            logger.error(errorMessage)
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
                    hint = errorMessage
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
            cause.printStackTrace()
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
        get("/config") {
            call.respond(Config())
            return@get
        }
        this.accountsMgmtHandlers()
        this.tokenHandlers()
        this.transactionsHandlers()
        this.talerWebHandlers()
        // this.walletIntegrationHandlers()
    }
}