package tech.libeufin.util

import UtilError
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.util.*
import logger
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URLDecoder

fun unauthorized(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.Unauthorized,
        msg,
        LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
    )
}

fun notFound(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.NotFound,
        msg,
        LibeufinErrorCode.LIBEUFIN_EC_NONE
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

fun forbidden(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.Forbidden,
        msg,
        ec = LibeufinErrorCode.LIBEUFIN_EC_NONE
    )
}

fun internalServerError(
    reason: String,
    libeufinErrorCode: LibeufinErrorCode? = LibeufinErrorCode.LIBEUFIN_EC_NONE
): UtilError {
    return UtilError(
        HttpStatusCode.InternalServerError,
        reason,
        ec = libeufinErrorCode
    )
}

fun badRequest(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.BadRequest,
        msg,
        ec = LibeufinErrorCode.LIBEUFIN_EC_NONE
    )
}

fun conflict(msg: String): UtilError {
    return UtilError(
        HttpStatusCode.Conflict,
        msg,
        ec = LibeufinErrorCode.LIBEUFIN_EC_NONE
    )
}

/**
 * Get the base URL of a request; handles proxied case.
 */
fun ApplicationRequest.getBaseUrl(): String {
    return if (this.headers.contains("X-Forwarded-Host")) {
        logger.info("Building X-Forwarded- base URL")
        /**
         * FIXME: should tolerate a missing X-Forwarded-Prefix.
         */
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
 * Get the URI (path's) component or throw Internal server error.
 * @param component the name of the URI component to return.
 */
fun ApplicationCall.getUriComponent(name: String): String {
    val ret: String? = this.parameters[name]
    if (ret == null) throw internalServerError("Component $name not found in URI")
    return ret
}

/**
 * Throw "unauthorized" if the request is not
 * authenticated by "admin", silently return otherwise.
 *
 * @param username who made the request.
 */
fun expectAdmin(username: String?) {
    if (username == null) {
        logger.info("Skipping 'admin' authentication for tests.")
        return
    }
    if (username != "admin") throw unauthorized("Only admin allowed: $username is not.")
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
    // logger.debug("Found Authorization header: $authorization")
    return authorization ?: throw UtilError(
        HttpStatusCode.Unauthorized, "Authorization header not found",
        LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
    )
}

// Builds the Authorization:-header value, given the credentials.
fun buildBasicAuthLine(username: String, password: String): String {
    val ret = "Basic "
    val cred = "$username:$password"
    val enc = bytesToBase64(cred.toByteArray(Charsets.UTF_8))
    return ret+enc
}
/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndPassword(authorizationHeader: String): Pair<String, String> {
    // logger.debug("Authenticating: $authorizationHeader")
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
