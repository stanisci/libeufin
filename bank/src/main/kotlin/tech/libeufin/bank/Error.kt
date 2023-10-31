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


fun forbidden(
    hint: String = "No rights on the resource",
    talerErrorCode: TalerErrorCode = TalerErrorCode.END
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Forbidden, talerError = TalerError(
        code = talerErrorCode.code, hint = hint
    )
)

fun unauthorized(hint: String = "Login failed"): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Unauthorized, talerError = TalerError(
        code = TalerErrorCode.GENERIC_UNAUTHORIZED.code, hint = hint
    )
)

fun internalServerError(hint: String?): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.InternalServerError, talerError = TalerError(
        code = TalerErrorCode.GENERIC_INTERNAL_INVARIANT_FAILURE.code, hint = hint
    )
)

fun notFound(
    hint: String?,
    talerEc: TalerErrorCode
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.NotFound, talerError = TalerError(
        code = talerEc.code, hint = hint
    )
)

fun conflict(
    hint: String?, talerEc: TalerErrorCode
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.Conflict, talerError = TalerError(
        code = talerEc.code, hint = hint
    )
)

fun badRequest(
    hint: String? = null, talerErrorCode: TalerErrorCode = TalerErrorCode.GENERIC_JSON_INVALID
): LibeufinBankException = LibeufinBankException(
    httpStatus = HttpStatusCode.BadRequest, talerError = TalerError(
        code = talerErrorCode.code, hint = hint
    )
)

fun BankConfig.checkInternalCurrency(amount: TalerAmount) {
    if (amount.currency != currency) throw badRequest(
        "Wrong currency: expected internal currency $currency got ${amount.currency}",
        talerErrorCode = TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}

fun BankConfig.checkFiatCurrency(amount: TalerAmount) {
    if (amount.currency != fiatCurrency) throw badRequest(
        "Wrong currency: expected fiat currency $fiatCurrency got ${amount.currency}",
        talerErrorCode = TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}