package tech.libeufin.util

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.util.*
import io.ktor.util.*
import logger

// Get the base URL of a request; handles proxied case.
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
                name = this.headers.get("X-Forwarded-Proto") ?: run {
                    logger.error("Reverse proxy did not define X-Forwarded-Proto")
                    return null
                },
                defaultPort = -1 // Port must be specified with X-Forwarded-Host.
            ),
            host = this.headers.get("X-Forwarded-Host") ?: run {
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

/**
 * Get the URI (path's) component or throw Internal server error.
 * @param component the name of the URI component to return.
 */
fun ApplicationCall.expectUriComponent(name: String): String? {
    val ret: String? = this.parameters[name]
    if (ret == null) {
        logger.error("Component $name not found in URI")
        return null
    }
    return ret
}

// Extracts the Authorization:-header line, or returns null if not found.
fun getAuthorizationRawHeader(request: ApplicationRequest): String? {
    val authorization = request.headers["Authorization"]
    return authorization ?: run {
        logger.error("Authorization header not found")
        return null
    }
}

/**
 * Holds the details contained in an Authorization header.
 * The content is held as it was found in the header and supposed
 * to be processed according to the scheme.
 */
data class AuthorizationDetails(
    val scheme: String,
    val content: String
)
// Returns the authorization scheme mentioned in the Auth header,
// or null if that could not be found.
fun getAuthorizationDetails(authorizationHeader: String): AuthorizationDetails? {
    val split = authorizationHeader.split(" ")
    if (split.isEmpty()) {
        logger.error("malformed Authorization header: contains no space")
        return null
    }
    if (split.size != 2) {
        logger.error("malformed Authorization header: contains more than one space")
        return null
    }
    return AuthorizationDetails(scheme = split[0], content = split[1])
}

// Gets a long from the URI param named 'uriParamName',
// or null if that is not found.
fun ApplicationCall.maybeLong(uriParamName: String): Long? {
    val maybeParam = this.parameters[uriParamName] ?: return null
    return try { maybeParam.toLong() }
    catch (e: Exception) {
        logger.error("Could not convert '$uriParamName' to Long")
        return null
    }
}