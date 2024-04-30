/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

package tech.libeufin.common.api

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.common.*
import tech.libeufin.common.db.dbInit
import tech.libeufin.common.db.pgDataSource
import java.net.InetAddress
import java.sql.SQLException
import java.time.Instant
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * This plugin checks for body length limit and inflates the requests that have "Content-Encoding: deflate"
 */
fun bodyLimitPlugin(logger: Logger): ApplicationPlugin<Unit> {
    return createApplicationPlugin("BodyLimitAndDecompression") {
        onCallReceive { call ->
            // TODO check content length as an optimisation
            transformBody { body ->
                val bytes = ByteArray(MAX_BODY_LENGTH.toInt() + 1)
                var read = 0
                when (val encoding = call.request.headers[HttpHeaders.ContentEncoding])  {
                    "deflate" -> {
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
                                throw badRequest("Decompressed body is suspiciously big > $MAX_BODY_LENGTH B")
                        }
                    }
                    null -> {
                        // Check body length
                        while (true) {
                            val new = body.readAvailable(bytes, read, bytes.size - read)
                            if (new == -1) break // Channel is closed
                            read += new
                            if (read > MAX_BODY_LENGTH)
                                throw badRequest("Body is suspiciously big > $MAX_BODY_LENGTH B")
                        }
                    } 
                    else -> throw unsupportedMediaType(
                        "Content encoding '$encoding' not supported, expected plain or deflate",
                        TalerErrorCode.GENERIC_COMPRESSION_INVALID
                    )
                }
                ByteReadChannel(bytes, 0, read)
            }
        }
    }
}

/** Set up web server handlers for a Taler API */
fun Application.talerApi(logger: Logger, routes: Routing.() -> Unit) {
    install(CallLogging) {
        level = Level.INFO
        this.logger = logger
        format { call ->
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
    install(XForwardedHeaders)
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(bodyLimitPlugin(logger))
    install(IgnoreTrailingSlash)
    install(ContentNegotiation) {
        json(Json {
            @OptIn(ExperimentalSerializationApi::class)
            explicitNulls = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        })
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.err(
                status,
                "There is no endpoint defined for the URL provided by the client. Check if you used the correct URL and/or file a report with the developers of the client software.",
                TalerErrorCode.GENERIC_ENDPOINT_UNKNOWN
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.err(
                status,
                "The HTTP method used is invalid for this endpoint. This is likely a bug in the client implementation. Check if you are using the latest available version and/or file a report with the developers.",
                TalerErrorCode.GENERIC_METHOD_INVALID
            )
        }
        exception<Exception> { call, cause ->
            logger.debug("request failed", cause)
            // TODO nexus specific error code ?!
            when (cause) {
                is ApiException -> call.err(cause)
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
                    val talerErrorCode = when {
                        cause is MissingRequestParameterException ->
                            TalerErrorCode.GENERIC_PARAMETER_MISSING
                        cause is ParameterConversionException ->
                            TalerErrorCode.GENERIC_PARAMETER_MALFORMED
                        rootCause is CommonError -> when (rootCause) {
                            is CommonError.AmountFormat -> TalerErrorCode.BANK_BAD_FORMAT_AMOUNT
                            is CommonError.AmountNumberTooBig -> TalerErrorCode.BANK_NUMBER_TOO_BIG
                            is CommonError.Payto -> TalerErrorCode.GENERIC_JSON_INVALID
                        }
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
    routing { routes() }
}

// Dirty local variable to stop the server in test TODO remove this ugly hack
var engine: ApplicationEngine? = null

fun serve(cfg: ServerConfig, api: Application.() -> Unit) {
    val env = applicationEngineEnvironment {
        when (cfg) {
            is ServerConfig.Tcp -> {
                for (addr in InetAddress.getAllByName(cfg.addr)) {
                    connector {
                        port = cfg.port
                        host = addr.hostAddress
                    }
                }
            }
            is ServerConfig.Unix ->
                throw Exception("Can only serve via TCP")
        }
        module { api() }
    }
    val local = embeddedServer(Netty, env)
    engine = local
    local.start(wait = true)
}