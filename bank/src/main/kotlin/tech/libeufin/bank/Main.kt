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

import TalerConfig
import TalerConfigError
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
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
import kotlin.system.exitProcess

// GLOBALS
private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Main")
const val GENERIC_UNDEFINED = -1 // Filler for ECs that don't exist yet.
val TOKEN_DEFAULT_DURATION_US = Duration.ofDays(1L).seconds * 1000000


/**
 * Application context with the parsed configuration.
 */
data class BankApplicationContext(
    /**
     * Main, internal currency of the bank.
     */
    val currency: String,
    /**
     * Restrict account registration to the administrator.
     */
    val restrictRegistration: Boolean,
    /**
     * Cashout currency, if cashouts are supported.
     */
    val cashoutCurrency: String?,
    /**
     * Default limit for the debt that a customer can have.
     * Can be adjusted per account after account creation.
     */
    val defaultCustomerDebtLimit: TalerAmount,
    /**
     * Debt limit of the admin account.
     */
    val defaultAdminDebtLimit: TalerAmount,
    /**
     * If true, transfer a registration bonus from the admin
     * account to the newly created account.
     */
    val registrationBonusEnabled: Boolean,
    /**
     * Only set if registration bonus is enabled.
     */
    val registrationBonus: TalerAmount?,
    /**
     * Exchange that the bank suggests to wallets for withdrawal.
     */
    val suggestedWithdrawalExchange: String?,
    /**
     * Max token duration in microseconds.
     */
    val maxAuthTokenDurationUs: Long,
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
        val dUs: Long =
            maybeDUs.longOrNull ?: throw badRequest("Could not convert d_us: '${maybeDUs.content}' to a number")
        return RelativeTime(d_us = dUs)
    }

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("RelativeTime") {
            element<JsonElement>("d_us")
        }
}

object TalerAmountSerializer : KSerializer<TalerAmount> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TalerAmount", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TalerAmount) {
        throw internalServerError("Encoding of TalerAmount not implemented.") // API doesn't require this.
    }

    override fun deserialize(decoder: Decoder): TalerAmount {
        val maybeAmount = try {
            decoder.decodeString()
        } catch (e: Exception) {
            throw badRequest(
                "Did not find any Taler amount as string: ${e.message}",
                TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
            )
        }
        return parseTalerAmount(maybeAmount)
    }
}


/**
 * Set up web server handlers for the Taler corebank API.
 */
fun Application.corebankWebApp(db: Database, ctx: BankApplicationContext) {
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
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            ignoreUnknownKeys = true
            // Registering custom parser for RelativeTime
            serializersModule = SerializersModule {
                contextual(RelativeTime::class) {
                    RelativeTimeSerializer
                }
                contextual(TalerAmount::class) {
                    TalerAmountSerializer
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
        exception<BadRequestException> { call, cause ->
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
            val talerErrorCode = when (cause) {
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
                )
            )
        }
        /**
         * This branch triggers when a bank handler throws it, and namely
         * after one logical failure of the request(-handling).  This branch
         * should be preferred to catch errors, as it allows to include the
         * Taler specific error detail.
         */
        exception<LibeufinBankException> { call, cause ->
            logger.error(cause.talerError.hint)
            call.respond(
                status = cause.httpStatus,
                message = cause.talerError
            )
        }
        // Catch-all branch to mean that the bank wasn't able to manage one error.
        exception<Exception> { call, cause ->
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
        this.accountsMgmtHandlers(db, ctx)
        this.talerIntegrationHandlers(db, ctx)
        this.talerWireGatewayHandlers(db, ctx)
    }
}

class LibeufinBankCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(ServeBank(), BankDbInit())
    }

    override fun run() = Unit
}

fun durationFromPretty(s: String): Long {
    var durationUs: Long = 0;
    var currentNum = "";
    var parsingNum = true
    for (c in s) {
        if (c >= '0' && c <= '9') {
            if (!parsingNum) {
                throw Error("invalid duration, unexpected number")
            }
            currentNum += c
            continue
        }
        if (c == ' ') {
            if (currentNum != "") {
                parsingNum = false
            }
            continue
        }
        if (currentNum == "") {
            throw Error("invalid duration, missing number")
        }
        val n = currentNum.toInt(10)
        durationUs += when (c) {
            's' -> { n * 1000000 }
            'm' -> { n * 1000000 * 60 }
            'h' -> { n * 1000000 * 60 * 60 }
            'd' -> { n * 1000000 * 60 * 60 * 24 }
            else -> { throw Error("invalid duration, unsupported unit '$c'") }
        }
        parsingNum = true
        currentNum = ""
    }
    return durationUs
}

/**
 * FIXME: Introduce a datatype for this instead of using Long
 */
fun TalerConfig.requireValueDuration(section: String, option: String): Long {
    val durationStr = lookupValueString(section, option)
    if (durationStr == null) {
        throw TalerConfigError("expected duration for section $section, option $option, but config value is empty")
    }
    return durationFromPretty(durationStr)
}

fun TalerConfig.requireValueAmount(section: String, option: String, currency: String): TalerAmount {
    val amountStr = lookupValueString(section, option)
    if (amountStr == null) {
        throw TalerConfigError("expected amount for section $section, option $option, but config value is empty")
    }
    val amount = parseTalerAmount2(amountStr, FracDigits.EIGHT)
    if (amount == null) {
        throw TalerConfigError("expected amount for section $section, option $option, but amount is malformed")
    }
    if (amount.currency != currency) {
        throw TalerConfigError(
            "expected amount for section $section, option $option, but currency is wrong (got ${amount.currency} expected $currency"
        )
    }
    return amount
}

/**
 * Read the configuration of the bank from a config file.
 * Throws an exception if the configuration is malformed.
 */
fun readBankApplicationContextFromConfig(cfg: TalerConfig): BankApplicationContext {
    val currency = cfg.requireValueString("libeufin-bank", "currency")
    return BankApplicationContext(
        currency = currency,
        restrictRegistration = cfg.lookupValueBooleanDefault("libeufin-bank", "restrict_registration", false),
        cashoutCurrency = cfg.lookupValueString("libeufin-bank", "cashout_currency"),
        defaultCustomerDebtLimit = cfg.requireValueAmount("libeufin-bank", "default_customer_debt_limit", currency),
        registrationBonusEnabled = cfg.lookupValueBooleanDefault("libeufin-bank", "registration_bonus_enabled", false),
        registrationBonus = cfg.requireValueAmount("libeufin-bank", "registration_bonus", currency),
        suggestedWithdrawalExchange = cfg.lookupValueString("libeufin-bank", "suggested_withdrawal_exchange"),
        defaultAdminDebtLimit = cfg.requireValueAmount("libeufin-bank", "default_admin_debt_limit", currency),
        maxAuthTokenDurationUs = cfg.requireValueDuration("libeufin-bank", "max_auth_token_duration"),
    )
}


class BankDbInit : CliktCommand("Initialize the libeufin-bank database", name = "dbinit") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )

    private val requestReset by option(
        "--reset", "-r",
        help = "reset database (DANGEROUS: All existing data is lost)"
    ).flag()

    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }

    override fun run() {
        val config = TalerConfig.load(this.configFile)
        val dbConnStr = config.requireValueString("libeufin-bankdb", "config")
        val sqlDir = config.requireValuePath("libeufin-bankdb-postgres", "sql_dir")
        if (requestReset) {
            resetDatabaseTables(dbConnStr, sqlDir)
        }
        initializeDatabaseTables(dbConnStr, sqlDir)
    }
}

class ServeBank : CliktCommand("Run libeufin-bank HTTP server", name = "serve") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }

    override fun run() {
        val config = TalerConfig.load(this.configFile)
        val ctx = readBankApplicationContextFromConfig(config)
        val dbConnStr = config.requireValueString("libeufin-bankdb", "config")
        logger.info("using database '$dbConnStr'")
        val serveMethod = config.requireValueString("libeufin-bank", "serve")
        if (serveMethod.lowercase() != "tcp") {
            logger.info("Can only serve libeufin-bank via TCP")
            exitProcess(1)
        }
        val servePortLong = config.requireValueNumber("libeufin-bank", "port")
        val servePort = servePortLong.toInt()
        val db = Database(dbConnStr, ctx.currency)
        if (!maybeCreateAdminAccount(db, ctx)) // logs provided by the helper
            exitProcess(1)
        embeddedServer(Netty, port = servePort) {
            corebankWebApp(db, ctx)
        }.start(wait = true)
    }
}

fun main(args: Array<String>) {
    LibeufinBankCommand().main(args)
}
