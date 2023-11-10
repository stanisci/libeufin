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

import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.*
import kotlinx.serialization.Serializable
import io.ktor.http.*

/**
 * Convenience type to throw errors along the bank activity
 * and that is meant to be caught by Ktor and responded to the
 * client.
 */
class LibeufinBankException(
    // Status code that Ktor will set for the response.
    val httpStatus: HttpStatusCode,
    // Error detail object, after Taler API.
    val talerError: TalerError
) : Exception(talerError.hint)

/**
 * Error object to respond to the client.  The
 * 'code' field takes values from the GANA gnu-taler-error-code
 * specification.  'hint' is a human-readable description
 * of the error.
 */
@Serializable
data class TalerError(
    val code: Int,
    val hint: String? = null,
    val detail: String? = null
)


fun libeufinError(
    status: HttpStatusCode,
    hint: String?,
    error: TalerErrorCode
): LibeufinBankException = LibeufinBankException(
    httpStatus = status, talerError = TalerError(
        code = error.code, hint = hint
    )
)

fun forbidden(
    hint: String = "No rights on the resource",
    error: TalerErrorCode = TalerErrorCode.END
): LibeufinBankException = libeufinError(HttpStatusCode.Forbidden, hint, error)

fun unauthorized(hint: String? = "Login failed"): LibeufinBankException 
    = libeufinError(HttpStatusCode.Unauthorized, hint, TalerErrorCode.GENERIC_UNAUTHORIZED)

fun internalServerError(hint: String?): LibeufinBankException 
    = libeufinError(HttpStatusCode.InternalServerError, hint, TalerErrorCode.GENERIC_INTERNAL_INVARIANT_FAILURE)

fun notFound(
    hint: String,
    error: TalerErrorCode
): LibeufinBankException = libeufinError(HttpStatusCode.NotFound, hint, error)

fun conflict(
    hint: String, error: TalerErrorCode
): LibeufinBankException = libeufinError(HttpStatusCode.Conflict, hint, error)

fun badRequest(
    hint: String? = null, error: TalerErrorCode = TalerErrorCode.GENERIC_JSON_INVALID
): LibeufinBankException = libeufinError(HttpStatusCode.BadRequest, hint, error)


fun BankConfig.checkRegionalCurrency(amount: TalerAmount) {
    if (amount.currency != currency) throw badRequest(
        "Wrong currency: expected regional currency $currency got ${amount.currency}",
        TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}

fun BankConfig.checkFiatCurrency(amount: TalerAmount) {
    if (amount.currency != fiatCurrency) throw badRequest(
        "Wrong currency: expected fiat currency $fiatCurrency got ${amount.currency}",
        TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}