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
import java.util.*

fun Routing.bankIntegrationApi(db: Database, ctx: BankConfig) {
    get("/taler-integration/config") {
        call.respond(TalerIntegrationConfigResponse(
            currency = ctx.currency,
            currency_specification = ctx.currencySpecification
        ))
    }

    // Note: wopid acts as an authentication token.
    get("/taler-integration/withdrawal-operation/{wopid}") {
        // TODO long poll
        val op = call.getWithdrawal(db, "wopid") // throws 404 if not found.
        val relatedBankAccount = db.bankAccountGetFromOwnerId(op.walletBankAccount)
            ?: throw internalServerError("Bank has a withdrawal not related to any bank account.")
        val suggestedExchange = ctx.suggestedWithdrawalExchange
        val confirmUrl = if (ctx.spaCaptchaURL == null) null else
            getWithdrawalConfirmUrl(
                baseUrl = ctx.spaCaptchaURL,
                wopId = op.withdrawalUuid
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
        val opId = call.uuidUriComponent("wopid")
        val req = call.receive<BankWithdrawalOperationPostRequest>()

        val (result, confirmationDone) = db.talerWithdrawalSetDetails(
            opId, req.selected_exchange, req.reserve_pub
        )
        when (result) {
            WithdrawalSelectionResult.OP_NOT_FOUND -> throw notFound(
                "Withdrawal operation $opId not found", 
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            WithdrawalSelectionResult.ALREADY_SELECTED -> throw conflict(
                "Cannot select different exchange and reserve pub. under the same withdrawal operation",
                TalerErrorCode.BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT
            )
            WithdrawalSelectionResult.RESERVE_PUB_REUSE -> throw conflict(
                "Reserve pub. already used", 
                TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
            WithdrawalSelectionResult.ACCOUNT_NOT_FOUND -> throw conflict(
                "Account ${req.selected_exchange.canonical} not found",
                TalerErrorCode.BANK_UNKNOWN_ACCOUNT
            )
            WithdrawalSelectionResult.ACCOUNT_IS_NOT_EXCHANGE -> throw conflict(
                "Account ${req.selected_exchange.canonical} is not an exchange",
                TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE
            )
            WithdrawalSelectionResult.SUCCESS -> {
                val confirmUrl: String? = if (ctx.spaCaptchaURL !== null && !confirmationDone) {
                    getWithdrawalConfirmUrl(
                        baseUrl = ctx.spaCaptchaURL,
                        wopId = opId
                    )
                } else null
                call.respond(BankWithdrawalOperationPostResponse(
                    transfer_done = confirmationDone, confirm_transfer_url = confirmUrl
                ))
            }
        }
    }
}