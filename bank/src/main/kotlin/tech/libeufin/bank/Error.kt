/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.

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
import io.ktor.server.response.*
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.*
/**
 * Convenience type to throw errors along the bank activity
 * and that is meant to be caught by Ktor and responded to the
 * client.
 */
class LibeufinException(
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
    @kotlinx.serialization.Transient val err: TalerErrorCode = TalerErrorCode.END,
    val code: Int,
    val hint: String? = null,
    val detail: String? = null
)

private val LOG_MSG = AttributeKey<String>("log_msg");

fun ApplicationCall.logMsg(): String? = attributes.getOrNull(LOG_MSG)

suspend fun ApplicationCall.err(
    status: HttpStatusCode,
    hint: String?,
    error: TalerErrorCode
) {
    err(
        LibeufinException(
            httpStatus = status, talerError = TalerError(
                code = error.code, err = error, hint = hint
            )
        )
    )
}

suspend fun ApplicationCall.err(
    err: LibeufinException
) {
    attributes.put(LOG_MSG, "${err.talerError.err.name} ${err.talerError.hint}")
    respond(
        status = err.httpStatus,
        message = err.talerError
    )
}


fun libeufinError(
    status: HttpStatusCode,
    hint: String?,
    error: TalerErrorCode,
    detail: String? = null
): LibeufinException = LibeufinException(
    httpStatus = status, talerError = TalerError(
        code = error.code, err = error, hint = hint, detail = detail
    )
)

/* ----- HTTP error ----- */

fun forbidden(
    hint: String = "No rights on the resource",
    error: TalerErrorCode = TalerErrorCode.END
): LibeufinException = libeufinError(HttpStatusCode.Forbidden, hint, error)

fun unauthorized(
    hint: String,
    error: TalerErrorCode = TalerErrorCode.GENERIC_UNAUTHORIZED
): LibeufinException = libeufinError(HttpStatusCode.Unauthorized, hint, error)

fun internalServerError(hint: String?): LibeufinException 
    = libeufinError(HttpStatusCode.InternalServerError, hint, TalerErrorCode.GENERIC_INTERNAL_INVARIANT_FAILURE)

fun notFound(
    hint: String,
    error: TalerErrorCode
): LibeufinException = libeufinError(HttpStatusCode.NotFound, hint, error)

fun conflict(
    hint: String, error: TalerErrorCode
): LibeufinException = libeufinError(HttpStatusCode.Conflict, hint, error)

fun badRequest(
    hint: String? = null, 
    error: TalerErrorCode = TalerErrorCode.GENERIC_JSON_INVALID,
    detail: String? = null
): LibeufinException = libeufinError(HttpStatusCode.BadRequest, hint, error, detail)


/* ----- Currency checks ----- */

fun BankConfig.checkRegionalCurrency(amount: TalerAmount) {
    if (amount.currency != regionalCurrency) throw badRequest(
        "Wrong currency: expected regional currency $regionalCurrency got ${amount.currency}",
        TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}

fun BankConfig.checkFiatCurrency(amount: TalerAmount) {
    if (amount.currency != fiatCurrency) throw badRequest(
        "Wrong currency: expected fiat currency $fiatCurrency got ${amount.currency}",
        TalerErrorCode.GENERIC_CURRENCY_MISMATCH
    )
}

/* ----- Common errors ----- */

fun unknownAccount(id: String): LibeufinException {
    return notFound(
        "Account '$id' not found",
        TalerErrorCode.BANK_UNKNOWN_ACCOUNT
    )
}

fun unsupportedTanChannel(channel: TanChannel): LibeufinException {
    return conflict(
        "Unsupported tan channel $channel",
        TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED
    )
}