package tech.libeufin.util

import UtilError
import io.ktor.http.*
import io.ktor.request.*
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

/**
 * Authenticate the HTTP request with a given token.  This one
 * is expected to comply with the RFC 8959 format; the function
 * throws an exception when the authentication fails
 *
 * @param tokenEnv is the authorization token that was found in the
 * environment.
 */
fun ApplicationRequest.authWithToken(tokenEnv: String?) {
    if (tokenEnv == null) {
        logger.info("Authenticating operation without any env token!")
        throw unauthorized("Authentication is not available now")
    }
    val auth = this.headers[HttpHeaders.Authorization] ?:
    throw unauthorized("Authorization header was not found in the request")
    val tokenReq = extractToken(auth)
    if (tokenEnv != tokenReq) throw unauthorized("Authentication failed, token did not match")
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
