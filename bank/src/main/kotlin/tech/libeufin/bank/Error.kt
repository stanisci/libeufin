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
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import tech.libeufin.common.*

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

fun unknownAccount(id: String): ApiException {
    return notFound(
        "Account '$id' not found",
        TalerErrorCode.BANK_UNKNOWN_ACCOUNT
    )
}

fun unknownCreditorAccount(id: String): ApiException {
    return conflict(
        "Creditor account '$id' not found",
        TalerErrorCode.BANK_UNKNOWN_CREDITOR
    )
}

fun unsupportedTanChannel(channel: TanChannel): ApiException {
    return conflict(
        "Unsupported tan channel $channel",
        TalerErrorCode.BANK_TAN_CHANNEL_NOT_SUPPORTED
    )
}