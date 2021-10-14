package tech.libeufin.util

import UtilError
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.util.*
import logger
import java.net.URLDecoder

private fun unauthorized(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.Unauthorized,
        msg,
        LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
    )
}



/**
 * Returns the token (including the 'secret-token:' prefix)
 * from a Authorization header.  Throws exception on malformations
 * Note, the token gets URL-decoded before being returned.
 */
fun extractToken(authHeader: String): String {
    val headerSplit = authHeader.split(" ", limit = 2)
    if (headerSplit.elementAtOrNull(0) != "Bearer") throw unauthorized("Authorization header does not start with 'Bearer'")
    val token = headerSplit.elementAtOrNull(1)
    if (token == null) throw unauthorized("Authorization header did not have the token")
    val tokenSplit = token.split(":", limit = 2)
    if (tokenSplit.elementAtOrNull(0) != "secret-token")
        throw unauthorized("Token lacks the 'secret-token:' prefix, see RFC 8959")
    val maybeToken = tokenSplit.elementAtOrNull(1)
    if(maybeToken == null || maybeToken == "")
        throw unauthorized("Actual token missing after the 'secret-token:' prefix")
    return "${tokenSplit[0]}:${URLDecoder.decode(tokenSplit[1], Charsets.UTF_8)}"
}

internal fun internalServerError(
    reason: String,
    libeufinErrorCode: LibeufinErrorCode? = LibeufinErrorCode.LIBEUFIN_EC_NONE
): UtilError {
    return UtilError(
        HttpStatusCode.InternalServerError,
        reason,
        ec = libeufinErrorCode
    )
}

/**
 * Get the base URL of a request; handles proxied case.
 */
fun ApplicationRequest.getBaseUrl(): String {
    return if (this.headers.contains("X-Forwarded-Host")) {
        logger.info("Building X-Forwarded- base URL")
        var prefix: String = this.headers.get("X-Forwarded-Prefix")
            ?: throw internalServerError("Reverse proxy did not define X-Forwarded-Prefix")
        if (!prefix.endsWith("/"))
            prefix += "/"
        URLBuilder(
            protocol = URLProtocol(
                name = this.headers.get("X-Forwarded-Proto") ?: throw internalServerError("Reverse proxy did not define X-Forwarded-Proto"),
                defaultPort = -1 // Port must be specified with X-Forwarded-Host.
            ),
            host = this.headers.get("X-Forwarded-Host") ?: throw internalServerError(
                "Reverse proxy did not define X-Forwarded-Host"
            ),
            encodedPath = prefix
        ).apply {
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
 * Authenticate the HTTP request with a given token.  This one
 * is expected to comply with the RFC 8959 format; the function
 * throws an exception when the authentication fails
 *
 * @param tokenEnv is the authorization token that was found in the
 * environment.
 */
fun ApplicationRequest.basicAuth() {
    val withAuth = this.call.ensureAttribute<Boolean>("withAuth")
    if (!withAuth) {
        logger.info("Authentication is disabled - assuming tests currently running.")
        return
    }
    val credentials = getHTTPBasicAuthCredentials(this)
    if (credentials.first == "admin") {
        // env must contain the admin password, because --with-auth is true.
        val adminPassword = this.call.ensureAttribute<String>("adminPassword")
        if (credentials.second != adminPassword) throw unauthorized(
            "Admin authentication failed"
        )
    }
    /**
     * TODO: extract customer hashed password from the database and check.
     */
}

fun getHTTPBasicAuthCredentials(request: ApplicationRequest): Pair<String, String> {
    val authHeader = getAuthorizationHeader(request)
    return extractUserAndPassword(authHeader)
}

/**
 * Extracts the Authorization:-header line and throws error if not found.
 */
fun getAuthorizationHeader(request: ApplicationRequest): String {
    val authorization = request.headers["Authorization"]
    return authorization ?: throw UtilError(
        HttpStatusCode.BadRequest, "Authorization header not found",
        LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
    )
}

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndPassword(authorizationHeader: String): Pair<String, String> {
    logger.debug("Authenticating: $authorizationHeader")
    val (username, password) = try {
        // FIXME/note: line below doesn't check for "Basic" presence.
        val split = authorizationHeader.split(" ")
        val plainUserAndPass = String(base64ToBytes(split[1]), Charsets.UTF_8)
        val ret = plainUserAndPass.split(":", limit = 2)
        if (ret.size < 2) throw java.lang.Exception(
            "HTTP Basic auth line does not contain username and password"
        )
        ret
    } catch (e: Exception) {
        throw UtilError(
            HttpStatusCode.BadRequest,
            "invalid Authorization:-header received: ${e.message}",
            LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
        )
    }
    return Pair(username, password)
}
