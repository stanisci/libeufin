/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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
package tech.libeufin.bank.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import tech.libeufin.bank.*
import tech.libeufin.bank.auth.authAdmin
import tech.libeufin.bank.db.ConversionDAO
import tech.libeufin.bank.db.ConversionDAO.ConversionResult
import tech.libeufin.bank.db.Database
import tech.libeufin.common.TalerAmount
import tech.libeufin.common.TalerErrorCode

fun Routing.conversionApi(db: Database, ctx: BankConfig) = conditional(ctx.allowConversion) {
    get("/conversion-info/config") {
        val config = db.conversion.getConfig(ctx.regionalCurrency, ctx.fiatCurrency!!)
        if (config == null) {
            throw libeufinError(
                HttpStatusCode.NotImplemented, 
                "conversion rate not configured yet", 
                TalerErrorCode.END
            )
        }
        call.respond(
            ConversionConfig(
                regional_currency = ctx.regionalCurrency,
                regional_currency_specification = ctx.regionalCurrencySpec,
                fiat_currency = ctx.fiatCurrency,
                fiat_currency_specification = ctx.fiatCurrencySpec!!,
                conversion_rate = config
            )
        )
    }
    suspend fun ApplicationCall.convert(
        input: TalerAmount, 
        conversion: suspend ConversionDAO.(TalerAmount) -> ConversionResult,
        output: (TalerAmount) -> ConversionResponse
    ) {
        when (val res = db.conversion.(conversion)(input)) {
            is ConversionResult.Success -> respond(output(res.converted))
            is ConversionResult.ToSmall -> throw conflict(
                "$input is too small to be converted",
                TalerErrorCode.BANK_BAD_CONVERSION
            )
            is ConversionResult.MissingConfig -> throw libeufinError(
                HttpStatusCode.NotImplemented, 
                "conversion rate not configured yet", 
                TalerErrorCode.END
            )
        }
    }
    get("/conversion-info/cashout-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkRegionalCurrency(it) }
        params.credit?.let { ctx.checkFiatCurrency(it) }

        if (params.debit != null) {
            call.convert(params.debit, ConversionDAO::toCashout) {
                ConversionResponse(params.debit, it)
            }
        } else {
            call.convert(params.credit!!, ConversionDAO::fromCashout) {
                ConversionResponse(it, params.credit)
            }
        }
    }
    get("/conversion-info/cashin-rate") {
        val params = RateParams.extract(call.request.queryParameters)

        params.debit?.let { ctx.checkFiatCurrency(it) }
        params.credit?.let { ctx.checkRegionalCurrency(it) }

        if (params.debit != null) {
            call.convert(params.debit, ConversionDAO::toCashin) {
                ConversionResponse(params.debit, it)
            }
        } else {
            call.convert(params.credit!!, ConversionDAO::fromCashin) {
                ConversionResponse(it, params.credit)
            }
        }
    }
    authAdmin(db, TokenScope.readwrite) {
        post("/conversion-info/conversion-rate") {
            val req = call.receive<ConversionRate>()
            for (regionalAmount in sequenceOf(req.cashin_fee, req.cashin_tiny_amount, req.cashout_min_amount)) {
                ctx.checkRegionalCurrency(regionalAmount)
            }
            for (fiatAmount in sequenceOf(req.cashout_fee, req.cashout_tiny_amount, req.cashin_min_amount)) {
                ctx.checkFiatCurrency(fiatAmount)
            }
            db.conversion.updateConfig(req)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}