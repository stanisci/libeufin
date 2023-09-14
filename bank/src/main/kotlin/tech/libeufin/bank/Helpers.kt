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
import tech.libeufin.util.*
import java.lang.NumberFormatException

// HELPERS.



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
                code = GENERIC_HTTP_HEADERS_MALFORMED, // 23
                "Malformed Basic auth credentials found in the Authorization header."
            )
        )
    val login = userAndPassSplit[0]
    val plainPassword = userAndPassSplit[1]
    val maybeCustomer = db.customerGetFromLogin(login) ?: return null
    if (!CryptoUtil.checkpw(plainPassword, maybeCustomer.passwordHash)) return null
    return maybeCustomer
}

/* Performs the bearer-token authentication.  Returns the
 * authenticated customer on success, null otherwise. */
fun doTokenAuth(
    token: String,
    requiredScope: TokenScope, // readonly or readwrite
): Customer? {
    val maybeToken: BearerToken = db.bearerTokenGet(token.toByteArray(Charsets.UTF_8)) ?: return null
    val isExpired: Boolean = maybeToken.expirationTime - getNow().toMicro() < 0
    if (isExpired || maybeToken.scope != requiredScope) return null // FIXME: mention the reason?
    // Getting the related username.
    return db.customerGetFromRowId(maybeToken.bankCustomer)
        ?: throw LibeufinBankException(
            httpStatus = HttpStatusCode.InternalServerError,
            talerError = TalerError(
                code = GENERIC_INTERNAL_INVARIANT_FAILURE,
                hint = "Customer not found, despite token mentions it.",
            ))
}

fun unauthorized(hint: String? = null): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.Unauthorized,
        talerError = TalerError(
            code = BANK_LOGIN_FAILED,
            hint = hint
        )
    )
fun internalServerError(hint: String): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError,
        talerError = TalerError(
            code = GENERIC_INTERNAL_INVARIANT_FAILURE,
            hint = hint
        )
    )
fun badRequest(
    hint: String? = null,
    talerErrorCode: Int = GENERIC_JSON_INVALID
): LibeufinBankException =
    LibeufinBankException(
        httpStatus = HttpStatusCode.InternalServerError,
        talerError = TalerError(
            code = talerErrorCode,
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
            code = BANK_BAD_FORMAT_AMOUNT,
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
                code = BANK_BAD_FORMAT_AMOUNT,
                hint = "Invalid amount: ${amount}, could not extract the value part."
            )
        )
    }
    return TalerAmount(
        value = value,
        frac = fraction
    )
}