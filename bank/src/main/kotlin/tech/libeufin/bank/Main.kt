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
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
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
import io.ktor.utils.io.jvm.javaio.*
import java.time.Duration
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.sql.SQLException
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.postgresql.util.PSQLState
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

// GLOBALS
private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Main")
val TOKEN_DEFAULT_DURATION: java.time.Duration = Duration.ofDays(1L)
private val MAX_BODY_LENGTH: Long = 4 * 1024 // 4kB

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
            logger.error(cause.message)
            var rootCause: Throwable? = cause.cause
            while (rootCause?.cause != null)
                rootCause = rootCause.cause
            /* Here getting _some_ error message, by giving precedence
             * to the root cause, as otherwise JSON details would be lost. */
            logger.error(rootCause?.message)
            // Telling apart invalid JSON vs missing parameter vs invalid parameter.
            val talerErrorCode = when (cause) {
                is MissingRequestParameterException ->
                    TalerErrorCode.GENERIC_PARAMETER_MISSING

                is ParameterConversionException ->
                    TalerErrorCode.GENERIC_PARAMETER_MALFORMED

                else -> TalerErrorCode.GENERIC_JSON_INVALID
            }
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = TalerError(
                    code = talerErrorCode.code,
                    hint = cause.message,
                    detail = rootCause?.message
                )
            )
        }
        exception<LibeufinBankException> { call, cause ->
            logger.error(cause.talerError.hint)
            call.respond(
                status = cause.httpStatus,
                message = cause.talerError
            )
        }
        exception<SQLException> { call, cause ->
            cause.printStackTrace()
            val err = when (cause.sqlState) {
                PSQLState.SERIALIZATION_FAILURE.state -> libeufinError(
                    HttpStatusCode.InternalServerError,
                    "Transaction serialization failure",
                    TalerErrorCode.BANK_SOFT_EXCEPTION
                )
                else -> libeufinError(
                    HttpStatusCode.InternalServerError,
                    "Unexpected sql error with state ${cause.sqlState}",
                    TalerErrorCode.BANK_UNMANAGED_EXCEPTION
                )
            }
            logger.error(err.talerError.hint)
            call.respond(
                status = err.httpStatus,
                message = err.talerError
            )
        }
        // Catch-all branch to mean that the bank wasn't able to manage one error.
        exception<Exception> { call, cause ->
            val err = libeufinError(
                HttpStatusCode.InternalServerError,
                cause.message,
                TalerErrorCode.BANK_UNMANAGED_EXCEPTION
            )
            logger.error(err.talerError.hint)
            call.respond(
                status = err.httpStatus,
                message = err.talerError
            )
        }
    }
    routing {
        coreBankApi(db, ctx)
        conversionApi(db, ctx)
        bankIntegrationApi(db, ctx)
        wireGatewayApi(db, ctx)
        revenueApi(db)
        ctx.spaPath?.let { 
            staticFiles("/", File(it))
        }
    }
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
            's' -> {
                n * 1000000
            }

            'm' -> {
                n * 1000000 * 60
            }

            'h' -> {
                n * 1000000 * 60 * 60
            }

            'd' -> {
                n * 1000000 * 60 * 60 * 24
            }

            else -> {
                throw Error("invalid duration, unsupported unit '$c'")
            }
        }
        parsingNum = true
        currentNum = ""
    }
    return durationUs
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

    override fun run() {
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
                AccountCreationResult.PayToReuse -> {
                    logger.error("Failed to create admin's account")
                    exitProcess(1)
                }
                AccountCreationResult.Success -> {
                    logger.info("Admin's account created")
                }
            }
        }
    }
}

class ServeBank : CliktCommand("Run libeufin-bank HTTP server", name = "serve") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )

    override fun run() {
        val cfg = talerConfig(configFile)
        val ctx = cfg.loadBankConfig()
        val dbCfg = cfg.loadDbConfig()
        val serverCfg = cfg.loadServerConfig()
        if (serverCfg.method.lowercase() != "tcp") {
            logger.info("Can only serve libeufin-bank via TCP")
            exitProcess(1)
        }
        val db = Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            if (ctx.allowConversion) {
                logger.info("ensure conversion is enabled")
                val sqlProcedures = File("${dbCfg.sqlDir}/libeufin-conversion-setup.sql")
                if (!sqlProcedures.exists()) {
                    logger.info("Missing libeufin-conversion-setup.sql file")
                    exitProcess(1)
                }
                pgDataSource(dbCfg.dbConnStr).pgConnection().execSQLUpdate(sqlProcedures.readText())
            } else {
                logger.info("ensure conversion is disabled")
                val sqlProcedures = File("${dbCfg.sqlDir}/libeufin-conversion-drop.sql")
                if (!sqlProcedures.exists()) {
                    logger.info("Missing libeufin-conversion-drop.sql file")
                    exitProcess(1)
                }
                pgDataSource(dbCfg.dbConnStr).pgConnection().execSQLUpdate(sqlProcedures.readText())
                // Remove conversion info from the database ?
            }
        }
        embeddedServer(Netty, port = serverCfg.port) {
            corebankWebApp(db, ctx)
        }.start(wait = true)
    }
}

class ChangePw : CliktCommand("Change account password", name = "passwd") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val account by argument("account")
    private val password by argument("password")

    override fun run() {
        val cfg = talerConfig(configFile)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()
        val db = Database(dbCfg.dbConnStr, ctx.regionalCurrency, ctx.fiatCurrency)
        runBlocking {
            if (db.account.reconfigPassword(account, password, null) != AccountPatchAuthResult.Success) {
                println("password change failed")
                exitProcess(1)
            } else {
                println("password change succeeded")
            }
        }
    }
}

class BankConfigDump : CliktCommand("Dump the configuration", name = "dump") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )

    override fun run() {
        val config = talerConfig(configFile)
        println("# install path: ${config.getInstallPath()}")
        config.load(this.configFile)
        println(config.stringify())
    }
}

class BankConfigPathsub : CliktCommand("Substitute variables in a path", name = "pathsub") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val pathExpr by argument()

    override fun run() {
        val config = talerConfig(configFile)
        println(config.pathsub(pathExpr))
    }
}

class BankConfigGet : CliktCommand("Lookup config value", name = "get") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val isPath by option(
        "--filename", "-f",
        help = "interpret value as path with dollar-expansion"
    ).flag()
    private val sectionName by argument()
    private val optionName by argument()


    override fun run() {
        val config = talerConfig(configFile)
        if (isPath) {
            val res = config.lookupPath(sectionName, optionName)
            if (res == null) {
                logger.info("value not found in config")
                exitProcess(2)
            }
            println(res)
        } else {
            val res = config.lookupString(sectionName, optionName)
            if (res == null) {
                logger.info("value not found in config")
                exitProcess(2)
            }
            println(res)
        }
    }
}

class BankConfigCmd : CliktCommand("Dump the configuration", name = "config") {
    init {
        subcommands(BankConfigDump(), BankConfigPathsub(), BankConfigGet())
    }

    override fun run() = Unit
}

class LibeufinBankCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(ServeBank(), BankDbInit(), ChangePw(), BankConfigCmd())
    }

    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinBankCommand().main(args)
}
