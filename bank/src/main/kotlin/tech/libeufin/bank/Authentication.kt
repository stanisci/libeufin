/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.Route
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import java.time.Instant
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*

private val AUTH_IS_ADMIN = AttributeKey<Boolean>("is_admin");

/** Restrict route access to admin */
fun Route.authAdmin(db: Database, scope: TokenScope, enforce: Boolean = true, callback: Route.() -> Unit): Route =
    intercept(callback) {
        if (enforce) {
            val login = context.authenticateBankRequest(db, scope) ?: throw unauthorized("Bad login")
            if (login != "admin") {
                throw unauthorized("Only administrator allowed")
            }
            context.attributes.put(AUTH_IS_ADMIN, true)
        } else {
            val login = try {
                context.authenticateBankRequest(db, scope) 
            } catch (e: Exception) {
                null
            }
            context.attributes.put(AUTH_IS_ADMIN, login == "admin")
        }
    }


/** Authenticate and check access rights */
fun Route.auth(db: Database, scope: TokenScope, allowAdmin: Boolean = false, requireAdmin: Boolean = false, callback: Route.() -> Unit): Route  =
    intercept(callback) {
        val authLogin = context.authenticateBankRequest(db, scope) ?: throw unauthorized("Bad login")
        if (requireAdmin && authLogin != "admin") {
            if (authLogin != "admin") {
                throw unauthorized("Only administrator allowed")
            }
        } else {
            val hasRight = authLogin == username || (allowAdmin && authLogin == "admin");
            if (!hasRight) {
                throw unauthorized("Customer $authLogin have no right on $username account")
            }
        }
        context.attributes.put(AUTH_IS_ADMIN, authLogin == "admin")
    }

val PipelineContext<Unit, ApplicationCall>.username: String get() = call.username
val PipelineContext<Unit, ApplicationCall>.isAdmin: Boolean get() = call.isAdmin
val ApplicationCall.username: String get() = expectUriComponent("USERNAME")
val ApplicationCall.isAdmin: Boolean get() = attributes.getOrNull(AUTH_IS_ADMIN) ?: false

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
    val header = getAuthorizationRawHeader(this.request)
    if (header == null) {
        // Basic auth challenge
        response.header(HttpHeaders.WWWAuthenticate, "Basic")
        throw unauthorized(
            "Authorization header not found.",
            TalerErrorCode.GENERIC_PARAMETER_MISSING
        )
    }
    
    val authDetails = getAuthorizationDetails(header) ?: throw badRequest(
        "Authorization is invalid.",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
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
        "Authorization header is malformed.", TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    if (authDetails.scheme == "Bearer") return splitBearerToken(authDetails.content) ?: throw throw badRequest(
        "Authorization header is malformed (could not strip the prefix from Bearer token).",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
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
    if (userAndPassSplit.size != 2) throw badRequest(
        "Malformed Basic auth credentials found in the Authorization header.",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    val (login, plainPassword) = userAndPassSplit
    val passwordHash = db.account.passwordHash(login) ?: throw unauthorized("Bad password")
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
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    val tokenBytes = try {
        Base32Crockford.decode(bareToken)
    } catch (e: Exception) {
        throw badRequest(
            e.message, TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val maybeToken: BearerToken? = db.token.get(tokenBytes)
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
    return db.account.login(maybeToken.bankCustomer) ?: throw libeufinError(
        HttpStatusCode.InternalServerError,
        "Customer not found, despite token mentions it.",
        TalerErrorCode.GENERIC_INTERNAL_INVARIANT_FAILURE
    )
}