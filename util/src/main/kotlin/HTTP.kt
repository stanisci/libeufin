package tech.libeufin.util

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util.HTTP")

// Get the base URL of a request, returns null if any problem occurs.
fun ApplicationRequest.getBaseUrl(): String? {
    return if (this.headers.contains("X-Forwarded-Host")) {
        logger.info("Building X-Forwarded- base URL")
        // FIXME: should tolerate a missing X-Forwarded-Prefix.
        var prefix: String = this.headers["X-Forwarded-Prefix"]
            ?: run {
                logger.error("Reverse proxy did not define X-Forwarded-Prefix")
                return null
            }
        if (!prefix.endsWith("/"))
            prefix += "/"
        URLBuilder(
            protocol = URLProtocol(
                name = this.headers["X-Forwarded-Proto"] ?: run {
                    logger.error("Reverse proxy did not define X-Forwarded-Proto")
                    return null
                },
                defaultPort = -1 // Port must be specified with X-Forwarded-Host.
            ),
            host = this.headers["X-Forwarded-Host"] ?: run {
                logger.error("Reverse proxy did not define X-Forwarded-Host")
                return null
            }
        ).apply {
            encodedPath = prefix
            // Gets dropped otherwise.
            if (!encodedPath.endsWith("/"))
                encodedPath += "/"
        }.buildString()
    } else {
        this.call.url {
            parameters.clear()
            encodedPath = "/"
        }
    }
}