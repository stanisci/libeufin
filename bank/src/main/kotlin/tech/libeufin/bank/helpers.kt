/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.util.*
import java.lang.NumberFormatException

// Get the auth token (stripped of the bearer-token:-prefix)
// IF the call was authenticated with it.
fun ApplicationCall.getAuthToken(): String? {
    val h = getAuthorizationRawHeader(this.request) ?: return null
    val authDetails = getAuthorizationDetails(h) ?: throw badRequest(
        "Authorization header is malformed.",
        TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    if (authDetails.scheme == "Bearer")
        return splitBearerToken(authDetails.content) ?: throw
        throw badRequest(
            "Authorization header is malformed (could not strip the prefix from Bearer token).",
            TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    return null // Not a Bearer token case.
}


/**
 * Performs the HTTP basic authentication.  Returns the
 * authenticated customer on success, or null otherwise.
 */
fun doBasicAuth(encodedCredentials: String): Customer? {
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
    if (userAndPassSplit.size != 2)
        throw LibeufinBankException(
            httpStatus = HttpStatusCode.BadRequest,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED.code,
                "Malformed Basic auth credentials found in the Authorization header."
            )
        )
    val login = userAndPassSplit[0]
    val plainPassword = userAndPassSplit[1]
    val maybeCustomer = db.customerGetFromLogin(login) ?: return null
    if (!CryptoUtil.checkpw(plainPassword, maybeCustomer.passwordHash)) return null
    return maybeCustomer
}

/**
 * This function takes a prefixed Bearer token, removes the
 * bearer-token:-prefix and returns it.  Returns null, if the
 * input is invalid.
 */
private fun splitBearerToken(tok: String): String? {
    val tokenSplit = tok.split(":", limit = 2)
    if (tokenSplit.size != 2) return null
    if (tokenSplit[0] != "bearer-token") return null
    return tokenSplit[1]
}

/* Performs the bearer-token authentication.  Returns the
 * authenticated customer on success, null otherwise. */
fun doTokenAuth(
    token: String,
    requiredScope: TokenScope,
): Customer? {
    val bareToken = splitBearerToken(token) ?: throw badRequest(
        "Bearer token malformed",
        talerErrorCode = TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
    )
    val tokenBytes = try {
        Base32Crockford.decode(bareToken)
    } catch (e: Exception) {
        throw badRequest(
            e.message,
            TalerErrorCode.TALER_EC_GENERIC_HTTP_HEADERS_MALFORMED
        )
    }
    val maybeToken: BearerToken? = db.bearerTokenGet(tokenBytes)
    if (maybeToken == null) {
        logger.error("Auth token not found")
        return null
    }
    if (maybeToken.expirationTime - getNowUs() < 0) {
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
    return db.customerGetFromRowId(maybeToken.bankCustomer)
        ?: throw LibeufinBankException(
            httpStatus = HttpStatusCode.InternalServerError,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
                hint = "Customer not found, despite token mentions it.",
            ))
}

fun forbidden(hint: String? = null, talerErrorCode: TalerErrorCode): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Forbidden,
        talerError = TalerError(
            code = talerErrorCode.code,
            hint = hint
        )
    )

fun unauthorized(hint: String? = null): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Unauthorized,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_BANK_LOGIN_FAILED.code,
            hint = hint
        )
    )
fun internalServerError(hint: String?): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_GENERIC_INTERNAL_INVARIANT_FAILURE.code,
            hint = hint
        )
    )
fun badRequest(
    hint: String? = null,
    talerErrorCode: TalerErrorCode = TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest,
        talerError = TalerError(
            code = talerErrorCode.code,
            hint = hint
        )
    )
// Generates a new Payto-URI with IBAN scheme.
fun genIbanPaytoUri(): String = "payto://iban/SANDBOXX/${getIban()}"

/**
 * This helper takes the serialized version of a Taler Amount
 * type and parses it into Libeufin's internal representation.
 * It returns a TalerAmount type, or throws a LibeufinBankException
 * it the input is invalid.  Such exception will be then caught by
 * Ktor, transformed into the appropriate HTTP error type, and finally
 * responded to the client.
 */
fun parseTalerAmount(
    amount: String,
    fracDigits: FracDigits = FracDigits.EIGHT
): TalerAmount {
    val format = when (fracDigits) {
        FracDigits.TWO -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?)?$"
        FracDigits.EIGHT -> "^([A-Z]+):([0-9]+)(\\.[0-9][0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?[0-9]?)?$"
    }
    val match = Regex(format).find(amount) ?: throw LibeufinBankException(
        httpStatus = HttpStatusCode.BadRequest,
        talerError = TalerError(
            code = TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT.code,
            hint = "Invalid amount: $amount"
        ))
    val _value = match.destructured.component2()
    // Fraction is at most 8 digits, so it's always < than MAX_INT.
    val fraction: Int = match.destructured.component3().run {
        var frac = 0
        var power = 100000000
        if (this.isNotEmpty())
            // Skips the dot and processes the fractional chars.
            this.substring(1).forEach { chr ->
                power /= 10
                frac += power * chr.digitToInt()
        }
        return@run frac
    }
    val value: Long = try {
        _value.toLong()
    } catch (e: NumberFormatException) {
        throw LibeufinBankException(
            httpStatus = HttpStatusCode.BadRequest,
            talerError = TalerError(
                code = TalerErrorCode.TALER_EC_BANK_BAD_FORMAT_AMOUNT.code,
                hint = "Invalid amount: ${amount}, could not extract the value part."
            )
        )
    }
    return TalerAmount(
        value = value,
        frac = fraction,
        maybeCurrency = match.destructured.component1()
    )
}