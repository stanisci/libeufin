/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.time.Duration
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.sql.SQLException
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.postgresql.util.PSQLState
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Main")

/**
 * This plugin check for body lenght limit and inflates the requests that have "Content-Encoding: deflate"
 */
val bodyPlugin = createApplicationPlugin("BodyLimitAndDecompression") {
    onCallReceive { call ->
        // TODO check content lenght as an optimisation
        transformBody { body ->
            val bytes = ByteArray(MAX_BODY_LENGTH.toInt() + 1)
            var read = 0;
            if (call.request.headers[HttpHeaders.ContentEncoding] == "deflate") {
                // Decompress and check decompressed length
                val inflater = Inflater()
                while (!body.isClosedForRead) {
                    body.read { buf ->
                        inflater.setInput(buf)
                        try {
                            read += inflater.inflate(bytes, read, bytes.size - read)
                        } catch (e: DataFormatException) {
                            logger.error("Deflated request failed to inflate: ${e.message}")
                            throw badRequest(
                                "Could not inflate request",
                                TalerErrorCode.GENERIC_COMPRESSION_INVALID
                            )
                        }
                    }
                    if (read > MAX_BODY_LENGTH)
                        throw badRequest("Decompressed body is suspiciously big")
                }
            } else {
                // Check body length
                while (!body.isClosedForRead) {
                    read += body.readAvailable(bytes, read, bytes.size - read)
                    if (read > MAX_BODY_LENGTH)
                        throw badRequest("Body is suspiciously big")
                }
            }
            ByteReadChannel(bytes, 0, read)
        }
    }
}

/**
 * Set up web server handlers for the Taler corebank API.
 */
fun Application.corebankWebApp(db: Database, ctx: BankConfig) {
    install(CallLogging) {
        this.level = Level.DEBUG
        this.logger = tech.libeufin.bank.logger
        this.format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val path = call.request.path()
            val msg = call.logMsg()
            if (msg != null) {
                "$status, $httpMethod $path, $msg"
            } else {
                "$status, $httpMethod $path"
            }
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
    install(bodyPlugin)
    install(IgnoreTrailingSlash)
    install(ContentNegotiation) {
        json(Json {
            @OptIn(ExperimentalSerializationApi::class)
            explicitNulls = false
            encodeDefaults = true
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
    install(StatusPages) {
        exception<Exception> { call, cause ->
            when (cause) {
                is LibeufinBankException -> call.err(cause)
                is SQLException -> {
                    when (cause.sqlState) {
                        PSQLState.SERIALIZATION_FAILURE.state -> call.err(
                            HttpStatusCode.InternalServerError,
                            "Transaction serialization failure",
                            TalerErrorCode.BANK_SOFT_EXCEPTION
                        )
                        else -> call.err(
                            HttpStatusCode.InternalServerError,
                            "Unexpected sql error with state ${cause.sqlState}",
                            TalerErrorCode.BANK_UNMANAGED_EXCEPTION
                        )
                    }
                }
                is BadRequestException -> {
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
                    // Telling apart invalid JSON vs missing parameter vs invalid parameter.
                    val talerErrorCode = when (cause) {
                        is MissingRequestParameterException ->
                            TalerErrorCode.GENERIC_PARAMETER_MISSING

                        is ParameterConversionException ->
                            TalerErrorCode.GENERIC_PARAMETER_MALFORMED

                        else -> TalerErrorCode.GENERIC_JSON_INVALID
                    }
                    call.err(
                        badRequest(
                            cause.message,
                            talerErrorCode,
                            /* Here getting _some_ error message, by giving precedence
                            * to the root cause, as otherwise JSON details would be lost. */
                            rootCause?.message
                        )
                    )
                }
                else -> {
                    call.err(
                        HttpStatusCode.InternalServerError,
                        cause.message,
                        TalerErrorCode.BANK_UNMANAGED_EXCEPTION
                    )
                }
            }
        }
    }
    routing {
        coreBankApi(db, ctx)
        conversionApi(db, ctx)
        bankIntegrationApi(db, ctx)
        wireGatewayApi(db, ctx)
        revenueApi(db)
        ctx.spaPath?.let {
            get("/") {
                call.respondRedirect("/webui/")
            }
            staticFiles("/webui/", File(it))
        }
    }
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

    override fun run() = cliCmd(logger){
        val config = talerConfig(configFile)
        val cfg = config.loadDbConfig()
        if (requestReset) {
            resetDatabaseTables(cfg, sqlFilePrefix = "libeufin-bank")
        }
        initializeDatabaseTables(cfg, sqlFilePrefix = "libeufin-bank")
        val ctx = config.loadBankConfig();
        val db = Database(cfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            // Create admin account if missing
            val res = maybeCreateAdminAccount(db, ctx) // logs provided by the helper
            when (res) {
                AccountCreationResult.BonusBalanceInsufficient -> {}
                AccountCreationResult.LoginReuse -> {}
                AccountCreationResult.PayToReuse -> 
                    throw Exception("Failed to create admin's account")
                AccountCreationResult.Success ->
                    logger.info("Admin's account created")
            }
        }
    }
}

class ServeBank : CliktCommand("Run libeufin-bank HTTP server", name = "serve") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )

    override fun run() = cliCmd(logger) {
        val cfg = talerConfig(configFile)
        val ctx = cfg.loadBankConfig()
        val dbCfg = cfg.loadDbConfig()
        val serverCfg = cfg.loadServerConfig()
        val db = Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            if (ctx.allowConversion) {
                logger.info("Ensure exchange account exists")
                val info = db.account.bankInfo("exchange")
                if (info == null) {
                    throw Exception("Exchange account missing: an exchange account named 'exchange' is required for conversion to be enabled")
                } else if (!info.isTalerExchange) {
                    throw Exception("Account is not an exchange: an exchange account named 'exchange' is required for conversion to be enabled")
                }
                logger.info("Ensure conversion is enabled")
                val sqlProcedures = File("${dbCfg.sqlDir}/libeufin-conversion-setup.sql")
                if (!sqlProcedures.exists()) {
                    throw Exception("Missing libeufin-conversion-setup.sql file")
                }
                db.conn { it.execSQLUpdate(sqlProcedures.readText()) }
            } else {
                logger.info("Ensure conversion is disabled")
                val sqlProcedures = File("${dbCfg.sqlDir}/libeufin-conversion-drop.sql")
                if (!sqlProcedures.exists()) {
                    throw Exception("Missing libeufin-conversion-drop.sql file")
                }
                db.conn { it.execSQLUpdate(sqlProcedures.readText()) }
                // Remove conversion info from the database ?
            }
        }
        
        val env = applicationEngineEnvironment {
            connector {
                when (serverCfg) {
                    is ServerConfig.Tcp -> {
                        port = serverCfg.port
                    }
                    is ServerConfig.Unix ->
                        throw Exception("Can only serve libeufin-bank via TCP")
                }
            }
            module { corebankWebApp(db, ctx) }
        }
        embeddedServer(Netty, env).start(wait = true)
    }
}

class ChangePw : CliktCommand("Change account password", name = "passwd") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val username by argument("username")
    private val password by argument("password")

    override fun run() = cliCmd(logger) {
        val cfg = talerConfig(configFile)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()
        val db = Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            val res = db.account.reconfigPassword(username, password, null)
            when (res) {
                AccountPatchAuthResult.UnknownAccount ->
                    throw Exception("Password change for '$username' account failed: unknown account")
                AccountPatchAuthResult.OldPasswordMismatch -> { /* Can never happen */ }
                AccountPatchAuthResult.Success ->
                    logger.info("Password change for '$username' account succeeded")
            }
        }
    }
}

class CreateAccount : CliktCommand("Create an account", name = "create-account") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val json: RegisterAccountRequest by argument().convert { Json.decodeFromString<RegisterAccountRequest>(it) }

    override fun run() = cliCmd(logger) {
        val cfg = talerConfig(configFile)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()
        val db = Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            if (RESERVED_ACCOUNTS.contains(json.username)) {
                throw Exception("Username '${json.username}' is reserved")
            }

            val internalPayto = json.internal_payto_uri ?: IbanPayTo(genIbanPaytoUri())
            val result = db.account.create(
                login = json.username,
                name = json.name,
                email = json.challenge_contact_data?.email,
                phone = json.challenge_contact_data?.phone,
                cashoutPayto = json.cashout_payto_uri,
                password = json.password,
                internalPaytoUri = internalPayto,
                isPublic = json.is_public,
                isTalerExchange = json.is_taler_exchange,
                maxDebt = ctx.defaultCustomerDebtLimit,
                bonus = if (!json.is_taler_exchange) ctx.registrationBonus 
                        else TalerAmount(0, 0, ctx.regionalCurrency)
            )

            when (result) {
                AccountCreationResult.BonusBalanceInsufficient ->
                    throw Exception("Insufficient admin funds to grant bonus")
                AccountCreationResult.LoginReuse ->
                    throw Exception("Account username reuse '${json.username}'")
                AccountCreationResult.PayToReuse ->
                    throw Exception("Bank internalPayToUri reuse '${internalPayto.canonical}'")
                AccountCreationResult.Success ->
                    logger.info("Account '${json.username}' created")
            }
        }
    }
}

class LibeufinBankCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(ServeBank(), BankDbInit(), CreateAccount(), ChangePw(), CliConfigCmd(BANK_CONFIG_SOURCE))
    }

    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinBankCommand().main(args)
}
