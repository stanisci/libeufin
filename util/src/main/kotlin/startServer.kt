package tech.libeufin.util

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.netty.channel.unix.Errors
import logger
import java.net.BindException
import kotlin.system.exitProcess

const val EAFNOSUPPORT = -97 // Netty defines errors negatively.
class StartServerOptions(
    var ipv4OnlyOpt: Boolean,
    val localhostOnlyOpt: Boolean,
    val portOpt: Int
)

// Core function starting the server.
private fun serverMain(options: StartServerOptions, app: Application.() -> Unit) {
    val server = embeddedServer(
        Netty,
        environment = applicationEngineEnvironment {
            connector {
                this.port = options.portOpt
                this.host = if (options.localhostOnlyOpt) "127.0.0.1" else "0.0.0.0"
            }
            if (!options.ipv4OnlyOpt) connector {
                this.port = options.portOpt
                this.host = if (options.localhostOnlyOpt) "[::1]" else "[::]"
            }
            module(app)
        },
        // Maybe remove this?  Was introduced
        // to debug concurrency issues..
        configure = {
            connectionGroupSize = 1
            workerGroupSize = 1
            callGroupSize = 1
        }
    )
    /**
     * NOTE: excepted server still need the stop(), otherwise
     * it leaves the port locked and prevents the IPv4 retry.
     */
    try {
        server.start(wait = true)
    } catch (e: Exception) {
        server.stop()
        logger.debug("Rethrowing: ${e.message}")
        throw e // Rethrowing for retry policies.
    }
}

// Wrapper function that retries when IPv6 fails.
fun startServerWithIPv4Fallback(
    options: StartServerOptions,
    app: Application.() -> Unit
) {
    var maybeRetry = false
    try {
        serverMain(options, app)
    } catch (e: Exception) {
        logger.warn(e.message)
        // Find reasons to retry.
        if (e is Errors.NativeIoException) {
            logger.debug("errno: ${e.expectedErr()}")
            if ((e.expectedErr() == EAFNOSUPPORT) && (!options.ipv4OnlyOpt))
                maybeRetry = true
        }
    }
    // Fail, if no retry policy applies.  The catch block above logged the error.
    if (!maybeRetry) {
        exitProcess(1)
    }
    logger.info("Retrying to start the server on IPv4")
    options.ipv4OnlyOpt = true
    try {
        serverMain(options, app)
    } catch (e: Exception) {
        logger.error(e.message)
        exitProcess(1)
    }
}