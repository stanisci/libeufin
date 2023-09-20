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

// This file contains the Taler Wire Gateway API handlers.

package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.getNowUs

fun Routing.talerWireGatewayHandlers() {
    get("/accounts/{USERNAME}/taler-wire-gateway/config") {
        val internalCurrency = db.configGet("internal_currency")
            ?: throw internalServerError("Could not find bank own currency.")
        call.respond(TWGConfigResponse(currency = internalCurrency))
        return@get
    }
    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        return@get
    }
    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        return@post
    }
    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
        val c = call.myAuth(TokenScope.readwrite) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = false)) throw forbidden()
        val req = call.receive<AddIncomingRequest>()
        val amount = parseTalerAmount(req.amount)
        val internalCurrency = db.configGet("internal_currency")
            ?: throw internalServerError("Bank didn't find own currency.")
        if (amount.currency != internalCurrency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        if (db.bankTransactionCheckExists(req.reserve_pub) != null)
            throw conflict(
                "Reserve pub. already used",
                TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
        val walletAccount = db.bankAccountGetFromInternalPayto(req.debit_account)
            ?: throw notFound(
                "debit_account not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
        val exchangeAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("exchange bank account not found, despite it's a customer")
        val txTimestamp = getNowUs()
        val op = BankInternalTransaction(
            debtorAccountId = walletAccount.expectRowId(),
            amount = amount,
            creditorAccountId = exchangeAccount.expectRowId(),
            transactionDate = txTimestamp,
            subject = req.reserve_pub
        )
        val res = db.bankTransactionCreate(op)
        /**
         * Other possible errors are highly unlikely, because of the
         * previous checks on the existence of the involved bank accounts.
         */
        if (res == Database.BankTransactionResult.CONFLICT)
            throw conflict(
                "Insufficient balance",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
        val rowId = db.bankTransactionCheckExists(req.reserve_pub)
            ?: throw internalServerError("Could not find the just inserted bank transaction")
        call.respond(
            AddIncomingResponse(
                row_id = rowId,
                timestamp = txTimestamp
        ))
        return@post
    }
}

