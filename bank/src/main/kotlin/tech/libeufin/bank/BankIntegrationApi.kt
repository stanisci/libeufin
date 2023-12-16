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

/* This file contains the Taler Integration API endpoints,
* that are typically requested by wallets.  */
package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.bank.WithdrawalDAO.*
import java.lang.AssertionError

fun Routing.bankIntegrationApi(db: Database, ctx: BankConfig) {
    get("/taler-integration/config") {
        call.respond(TalerIntegrationConfigResponse(
            currency = ctx.regionalCurrency,
            currency_specification = ctx.regionalCurrencySpec
        ))
    }

    // Note: wopid acts as an authentication token.
    get("/taler-integration/withdrawal-operation/{wopid}") {
        val uuid = call.uuidUriComponent("wopid")
        val params = StatusParams.extract(call.request.queryParameters)
        val op = db.withdrawal.pollStatus(uuid, params) ?: throw notFound(
            "Withdrawal operation '$uuid' not found", 
            TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
        )
        call.respond(op.copy(
            suggested_exchange = ctx.suggestedWithdrawalExchange,
            confirm_transfer_url = ctx.spaCaptchaURL?.run {
                getWithdrawalConfirmUrl(
                    baseUrl = this,
                    wopId = uuid
                )
            }
        ))
    }
    post("/taler-integration/withdrawal-operation/{wopid}") {
        val opId = call.uuidUriComponent("wopid")
        val req = call.receive<BankWithdrawalOperationPostRequest>()

        val res = db.withdrawal.setDetails(
            opId, req.selected_exchange, req.reserve_pub
        )
        when (res) {
            is WithdrawalSelectionResult.UnknownOperation -> throw notFound(
                "Withdrawal operation $opId not found", 
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            is WithdrawalSelectionResult.AlreadySelected -> throw conflict(
                "Cannot select different exchange and reserve pub. under the same withdrawal operation",
                TalerErrorCode.BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT
            )
            is WithdrawalSelectionResult.RequestPubReuse -> throw conflict(
                "Reserve pub. already used", 
                TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
            is WithdrawalSelectionResult.UnknownAccount -> throw conflict(
                "Account ${req.selected_exchange.canonical} not found",
                TalerErrorCode.BANK_UNKNOWN_ACCOUNT
            )
            is WithdrawalSelectionResult.AccountIsNotExchange -> throw conflict(
                "Account ${req.selected_exchange.canonical} is not an exchange",
                TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE
            )
            is WithdrawalSelectionResult.Success -> {
                val confirmUrl: String? = if (ctx.spaCaptchaURL !== null && res.status == WithdrawalStatus.selected) {
                    getWithdrawalConfirmUrl(
                        baseUrl = ctx.spaCaptchaURL,
                        wopId = opId
                    )
                } else null
                call.respond(BankWithdrawalOperationPostResponse(
                    transfer_done = res.status == WithdrawalStatus.confirmed, 
                    status = res.status,
                    confirm_transfer_url = confirmUrl
                ))
            }
        }
    }
    post("/taler-integration/withdrawal-operation/{wopid}/abort") {
        val opId = call.uuidUriComponent("wopid")
        when (db.withdrawal.abort(opId)) {
            AbortResult.UnknownOperation -> throw notFound(
                "Withdrawal operation $opId not found",
                TalerErrorCode.BANK_TRANSACTION_NOT_FOUND
            )
            AbortResult.AlreadyConfirmed -> throw conflict(
                "Cannot abort confirmed withdrawal", 
                TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT
            )
            AbortResult.Success -> call.respond(HttpStatusCode.NoContent)
        }
    }
}