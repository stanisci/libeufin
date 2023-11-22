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
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import tech.libeufin.util.*
import net.taler.common.errorcodes.TalerErrorCode

fun Routing.conversionApi(db: Database, ctx: BankConfig) = conditional(ctx.allowConversion) {
    get("/conversion-info/config") {
        call.respond(
            ConversionConfig(
                regional_currency = ctx.regionalCurrency,
                regional_currency_specification = ctx.regionalCurrencySpec,
                fiat_currency = ctx.fiatCurrency!!,
                fiat_currency_specification = ctx.fiatCurrencySpec!!,
                conversion_info = ctx.conversionInfo!!
            )
        )
    }
    get("/conversion-info/cashout-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkRegionalCurrency(it) }
        params.credit?.let { ctx.checkFiatCurrency(it) }

        if (params.debit != null) {
            val credit = db.conversion.toCashout(params.debit) ?:
                throw conflict(
                    "${params.debit} is too small to be converted",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
            call.respond(ConversionResponse(params.debit, credit))
        } else {
            val debit = db.conversion.fromCashout(params.credit!!) ?:
            throw conflict(
                "${params.debit} is too small to be converted",
                TalerErrorCode.BANK_BAD_CONVERSION
            )
            call.respond(ConversionResponse(debit, params.credit))
        }
    }
    get("/conversion-info/cashin-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        if (params.debit != null) {
            ctx.checkFiatCurrency(params.debit)
            val credit = db.conversion.toCashin(params.debit) ?:
                throw conflict(
                    "${params.debit} is too small to be converted",
                    TalerErrorCode.BANK_BAD_CONVERSION
                )
            call.respond(ConversionResponse(params.debit, credit))
        } else {
            ctx.checkRegionalCurrency(params.credit!!)
            val debit = db.conversion.fromCashin(params.credit) ?:
            throw conflict(
                "${params.credit} is too small to be converted",
                TalerErrorCode.BANK_BAD_CONVERSION
            )
            call.respond(ConversionResponse(debit, params.credit))
        }
    }
}