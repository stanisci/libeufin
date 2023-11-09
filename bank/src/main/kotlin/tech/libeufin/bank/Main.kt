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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.http.*
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
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.getVersion
import tech.libeufin.util.initializeDatabaseTables
import tech.libeufin.util.resetDatabaseTables
import tech.libeufin.bank.libeufinError
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.InflaterInputStream
import java.util.zip.Inflater
import java.util.zip.DataFormatException
import kotlin.system.exitProcess

// GLOBALS
private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Main")
val TOKEN_DEFAULT_DURATION: java.time.Duration = Duration.ofDays(1L)
private val MAX_BODY_LENGTH: Long = 4 * 1024 // 4kB

/**
 * This plugin check for body lenght limit and inflates the requests that have "Content-Encoding: deflate"
 */
val bodyPlugin = createApplicationPlugin("BodyLimitAndDecompression") {
    onCall {
        val contentLenght = it.request.contentLength() 
            ?: throw badRequest("Missing Content-Length header", TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED)
    
        if (contentLenght > MAX_BODY_LENGTH) {
            throw badRequest("Body is suspiciously big")
        }
    }
    onCallReceive { call ->
        transformBody { data ->
            if (call.request.headers[HttpHeaders.ContentEncoding] == "deflate") {
                val inflater = Inflater()
                val bytes = ByteArray(MAX_BODY_LENGTH.toInt())
                var decoded = 0;

                while (!inflater.finished()) {
                    if (decoded == bytes.size) {
                        throw badRequest("Decompressed body is suspiciously big")
                    }
                    data.read {
                        inflater.setInput(it)
                        try {
                            decoded += inflater.inflate(bytes, decoded, bytes.size - decoded)
                        } catch (e: DataFormatException) {
                            logger.error("Deflated request failed to inflate: ${e.message}")
                            throw badRequest(
                                "Could not inflate request",
                                TalerErrorCode.GENERIC_COMPRESSION_INVALID
                            )
                        }
                    }
                }

                ByteReadChannel(bytes.copyOf(decoded))
            } else data
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
        /**
         * This branch triggers when a bank handler throws it, and namely
         * after one logical failure of the request(-handling).  This branch
         * should be preferred to catch errors, as it allows to include the
         * Taler specific error detail.
         */
        exception<LibeufinBankException> { call, cause ->
            logger.error(cause.talerError.hint)
            // Stacktrace if bank's fault
            if (cause.httpStatus.toString().startsWith('5'))
                cause.printStackTrace()
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
                    code = TalerErrorCode.GENERIC_INTERNAL_INVARIANT_FAILURE.code,
                    hint = cause.message
                )
            )
        }
    }
    routing {
        coreBankApi(db, ctx)
        bankIntegrationApi(db, ctx)
        wireGatewayApi(db, ctx)
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
        val cfg = talerConfig(configFile).loadDbConfig()
        if (requestReset) {
            resetDatabaseTables(cfg, sqlFilePrefix = "libeufin-bank")
        }
        initializeDatabaseTables(cfg, sqlFilePrefix = "libeufin-bank")
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
        val db = Database(dbCfg.dbConnStr, ctx.currency, ctx.fiatCurrency)
        runBlocking {
            if (!maybeCreateAdminAccount(db, ctx)) // logs provided by the helper
                exitProcess(1)
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
        val db = Database(dbCfg.dbConnStr, ctx.currency, ctx.fiatCurrency)
        runBlocking {
            if (!maybeCreateAdminAccount(db, ctx)) // logs provided by the helper
            exitProcess(1)

            if (!db.customerChangePassword(account, CryptoUtil.hashpw(password))) {
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
