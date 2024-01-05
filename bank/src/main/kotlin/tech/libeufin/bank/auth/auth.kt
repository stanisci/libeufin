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
package tech.libeufin.bank.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.Route
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import java.time.Instant
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.bank.db.AccountDAO.*
import tech.libeufin.bank.db.*
import tech.libeufin.bank.*
import tech.libeufin.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.bank.Authentication")

/** Used to store if the currenly authenticated user is admin */
private val AUTH_IS_ADMIN = AttributeKey<Boolean>("is_admin");
/** Used to store used auth token */
private val AUTH_TOKEN = AttributeKey<ByteArray>("auth_token");

/** Get username of the request account */
val ApplicationCall.username: String get() = expectParameter("USERNAME")
/** Get username of the request account */
val PipelineContext<Unit, ApplicationCall>.username: String get() = call.username

/** Check if current auth account is admin */
val ApplicationCall.isAdmin: Boolean get() = attributes.getOrNull(AUTH_IS_ADMIN) ?: false
/** Check if current auth account is admin */
val PipelineContext<Unit, ApplicationCall>.isAdmin: Boolean get() = call.isAdmin

/** Check auth token used for authentification */
val ApplicationCall.authToken: ByteArray? get() = attributes.getOrNull(AUTH_TOKEN)

/** 
 * Create an admin authenticated route for [scope].
 * 
 * If [enforce], only admin can access this route.
 * 
 * You can check is the currently authenticated user is admin using [isAdmin].
 **/
fun Route.authAdmin(db: Database, scope: TokenScope, enforce: Boolean = true, callback: Route.() -> Unit): Route =
    intercept(callback) {
        if (enforce) {
            val login = context.authenticateBankRequest(db, scope)
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


/** 
 * Create an authenticated route for [scope].
 * 
 * If [allowAdmin], admin is allowed to auth for any user.
 * If [requireAdmin], only admin can access this route.
 * 
 * You can check is the currently authenticated user is admin using [isAdmin].
 **/
fun Route.auth(db: Database, scope: TokenScope, allowAdmin: Boolean = false, requireAdmin: Boolean = false, callback: Route.() -> Unit): Route  =
    intercept(callback) {
        val authLogin = context.authenticateBankRequest(db, scope)
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

/**
 * Authenticate an HTTP request for [requiredScope] according to the scheme that is mentioned 
 * in the Authorization header.
 * The allowed schemes are either 'Basic' or 'Bearer'.
 *
 * Returns the authenticated customer login.
 */
private suspend fun ApplicationCall.authenticateBankRequest(db: Database, requiredScope: TokenScope): String {
    val header = request.headers["Authorization"]
    
    // Basic auth challenge
    if (header == null) {
        response.header(HttpHeaders.WWWAuthenticate, "Basic")
        throw unauthorized(
            "Authorization header not found",
            TalerErrorCode.GENERIC_PARAMETER_MISSING
        )
    }

    // Parse header
    val (scheme, content) = header.splitOnce(" ") ?: throw badRequest(
        "Authorization is invalid",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    return when (scheme) {
        "Basic" -> doBasicAuth(db, content)
        "Bearer" -> doTokenAuth(db, content, requiredScope)
        else -> throw unauthorized("Authorization method wrong or not supported")
    }
}

/**
 * Performs the HTTP Basic Authentication.
 * 
 * Returns the authenticated customer login
 */
private suspend fun doBasicAuth(db: Database, encoded: String): String {
    val decoded = String(base64ToBytes(encoded), Charsets.UTF_8)
    val (login, plainPassword) = decoded.splitOnce(":") ?: throw badRequest(
        "Malformed Basic auth credentials found in the Authorization header",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    val hash = db.account.passwordHash(login) ?: throw unauthorized("Unknown account")
    if (!CryptoUtil.checkpw(plainPassword, hash)) throw unauthorized("Bad password")
    return login
}

/**
 * Performs the secret-token HTTP Bearer Authentication.
 * 
 * Returns the authenticated customer login
 */
private suspend fun ApplicationCall.doTokenAuth(
    db: Database,
    bearer: String,
    requiredScope: TokenScope,
): String {
    if (!bearer.startsWith("secret-token:")) throw badRequest(
        "Bearer token malformed",
        TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
    )
    val decoded = try {
        Base32Crockford.decode(bearer.slice(13..bearer.length-1))
    } catch (e: Exception) {
        throw badRequest(
            e.message, TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val token: BearerToken = db.token.get(decoded) ?: throw unauthorized("Unknown token")
    when {
        token.expirationTime.isBefore(Instant.now()) 
            -> throw unauthorized("Expired auth token")

        token.scope == TokenScope.readonly && requiredScope == TokenScope.readwrite 
            -> throw unauthorized("Auth token has insufficient scope")

        !token.isRefreshable && requiredScope == TokenScope.refreshable 
            -> throw unauthorized("Unrefreshable token")
    }

    attributes.put(AUTH_TOKEN, decoded)

    return token.login
}