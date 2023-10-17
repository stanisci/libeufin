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

/* This file contains the Taler Integration API endpoints,
* that are typically requested by wallets.  */
package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode

fun Routing.bankIntegrationApi(db: Database, ctx: BankApplicationContext) {
    get("/taler-integration/config") {
        call.respond(TalerIntegrationConfigResponse(
            currency = ctx.currency,
            currency_specification = ctx.currencySpecification
        ))
    }

    // Note: wopid acts as an authentication token.
    get("/taler-integration/withdrawal-operation/{wopid}") {
        val wopid = call.expectUriComponent("wopid")
        val op = getWithdrawal(db, wopid) // throws 404 if not found.
        val relatedBankAccount = db.bankAccountGetFromOwnerId(op.walletBankAccount)
            ?: throw internalServerError("Bank has a withdrawal not related to any bank account.")
        val suggestedExchange = ctx.suggestedWithdrawalExchange
        val confirmUrl = if (ctx.spaCaptchaURL == null) null else
            getWithdrawalConfirmUrl(
                baseUrl = ctx.spaCaptchaURL,
                wopId = wopid
            )
        call.respond(
            BankWithdrawalOperationStatus(
                aborted = op.aborted,
                selection_done = op.selectionDone,
                transfer_done = op.confirmationDone,
                amount = op.amount,
                sender_wire = relatedBankAccount.internalPaytoUri.canonical,
                suggested_exchange = suggestedExchange,
                confirm_transfer_url = confirmUrl
            )
        )
    }
    post("/taler-integration/withdrawal-operation/{wopid}") {
        val wopid = call.expectUriComponent("wopid")
        val req = call.receive<BankWithdrawalOperationPostRequest>()
        val op = getWithdrawal(db, wopid) // throws 404 if not found.
        if (op.selectionDone) { // idempotency
            if (op.selectedExchangePayto != req.selected_exchange && op.reservePub != req.reserve_pub) throw conflict(
                hint = "Cannot select different exchange and reserve pub. under the same withdrawal operation",
                talerEc = TalerErrorCode.TALER_EC_BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT
            )
        }
        val dbSuccess: Boolean = if (!op.selectionDone) { // Check if reserve pub. was used in _another_ withdrawal.
            if (db.bankTransactionCheckExists(req.reserve_pub)) throw conflict(
                "Reserve pub. already used", TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
            db.talerWithdrawalSetDetails(
                op.withdrawalUuid, req.selected_exchange, req.reserve_pub
            )
        } else { // Nothing to do in the database, i.e. we were successful
            true
        }
        if (!dbSuccess)
        // Whatever the problem, the bank missed it: respond 500.
            throw internalServerError("Bank failed at selecting the withdrawal.")
        // Getting user details that MIGHT be used later.
        val confirmUrl: String? = if (ctx.spaCaptchaURL !== null && !op.confirmationDone) {
            getWithdrawalConfirmUrl(
                baseUrl = ctx.spaCaptchaURL,
                wopId = wopid
            )
        } else null
        val resp = BankWithdrawalOperationPostResponse(
            transfer_done = op.confirmationDone, confirm_transfer_url = confirmUrl
        )
        call.respond(resp)
    }
}