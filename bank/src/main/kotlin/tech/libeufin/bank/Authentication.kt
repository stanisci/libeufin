package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.*
import net.taler.wallet.crypto.Base32Crockford
import java.time.Instant

/** Authenticate admin */
suspend fun ApplicationCall.authAdmin(db: Database, scope: TokenScope) {
    // TODO when all endpoints use this function we can use an optimized database request that only query the customer login
    val login = authenticateBankRequest(db, scope) ?: throw unauthorized("Bad login")
    if (login != "admin") {
        throw unauthorized("Only administrator allowed")
    }
}

/** Authenticate and check access rights */
suspend fun ApplicationCall.authCheck(db: Database, scope: TokenScope, withAdmin: Boolean = true, requireAdmin: Boolean = false): Pair<String, Boolean> {
    // TODO when all endpoints use this function we can use an optimized database request that only query the customer login
    val authLogin = authenticateBankRequest(db, scope) ?: throw unauthorized("Bad login")
    val login = accountLogin()
    if (requireAdmin && authLogin != "admin") {
        if (authLogin != "admin") {
            throw unauthorized("Only administrator allowed")
        }
    } else {
        val hasRight = authLogin == login || (withAdmin && authLogin == "admin");
        if (!hasRight) {
            throw unauthorized("Customer $authLogin have no right on $login account")
        }
    }
    return Pair(login, authLogin == "admin")
}

/**
 * This function tries to authenticate the call according
 * to the scheme that is mentioned in the Authorization header.
 * The allowed schemes are either 'HTTP basic auth' or 'bearer token'.
 *
 * requiredScope can be either "readonly" or "readwrite".
 *
 * Returns the authenticated customer login, or null if they failed.
 */
private suspend fun ApplicationCall.authenticateBankRequest(db: Database, requiredScope: TokenScope): String? {
    // Extracting the Authorization header.
    val header = getAuthorizationRawHeader(this.request) ?: throw badRequest(
        "Authorization header not found.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val authDetails = getAuthorizationDetails(header) ?: throw badRequest(
        "Authorization is invalid.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    return when (authDetails.scheme) {
        "Basic" -> doBasicAuth(db, authDetails.content)
        "Bearer" -> doTokenAuth(db, authDetails.content, requiredScope)
        else -> throw unauthorized("Authorization method wrong or not supported.")
    }
}

// Get the auth token (stripped of the bearer-token:-prefix)
// IF the call was authenticated with it.
fun ApplicationCall.getAuthToken(): String? {
    val h = getAuthorizationRawHeader(this.request) ?: return null
    val authDetails = getAuthorizationDetails(h) ?: throw badRequest(
        "Authorization header is malformed.", TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    if (authDetails.scheme == "Bearer") return splitBearerToken(authDetails.content) ?: throw throw badRequest(
        "Authorization header is malformed (could not strip the prefix from Bearer token).",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    return null // Not a Bearer token case.
}


/**
 * Performs the HTTP basic authentication.  Returns the
 * authenticated customer login on success, or null otherwise.
 */
private suspend fun doBasicAuth(db: Database, encodedCredentials: String): String? {
    val plainUserAndPass = String(base64ToBytes(encodedCredentials), Charsets.UTF_8) // :-separated
    val userAndPassSplit = plainUserAndPass.split(
        ":",
        /**
         * this parameter allows colons to occur in passwords.
         * Without this, passwords that have colons would be split
         * and become meaningless.
         */
        limit = 2
    )
    if (userAndPassSplit.size != 2) throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest, talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED.code,
            "Malformed Basic auth credentials found in the Authorization header."
        )
    )
    val (login, plainPassword) = userAndPassSplit
    val passwordHash = db.customerPasswordHashFromLogin(login) ?: throw unauthorized()
    if (!CryptoUtil.checkpw(plainPassword, passwordHash)) return null
    return login
}

/**
 * This function takes a prefixed Bearer token, removes the
 * secret-token:-prefix and returns it.  Returns null, if the
 * input is invalid.
 */
private fun splitBearerToken(tok: String): String? {
    val tokenSplit = tok.split(":", limit = 2)
    if (tokenSplit.size != 2) return null
    if (tokenSplit[0] != "secret-token") return null
    return tokenSplit[1]
}

/* Performs the secret-token authentication.  Returns the
 * authenticated customer login on success, null otherwise. */
private suspend fun doTokenAuth(
    db: Database,
    token: String,
    requiredScope: TokenScope,
): String? {
    val bareToken = splitBearerToken(token) ?: throw badRequest(
        "Bearer token malformed",
        talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val tokenBytes = try {
        Base32Crockford.decode(bareToken)
    } catch (e: Exception) {
        throw badRequest(
            e.message, TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val maybeToken: BearerToken? = db.bearerTokenGet(tokenBytes)
    if (maybeToken == null) {
        logger.error("Auth token not found")
        return null
    }
    if (maybeToken.expirationTime.isBefore(Instant.now())) {
        logger.error("Auth token is expired")
        return null
    }
    if (maybeToken.scope == TokenScope.readonly && requiredScope == TokenScope.readwrite) {
        logger.error("Auth token has insufficient scope")
        return null
    }
    if (!maybeToken.isRefreshable && requiredScope == TokenScope.refreshable) {
        logger.error("Could not refresh unrefreshable token")
        return null
    }
    // Getting the related username.
    return db.customerLoginFromId(maybeToken.bankCustomer) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError, talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
            hint = "Customer not found, despite token mentions it.",
        )
    )
}