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

/* This file contains all the Taler handlers that do NOT
 * communicate with wallets, therefore any handler that serves
 * to SPAs or CLI HTTP clients.
 */

package tech.libeufin.bank

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.util.getBaseUrl
import java.util.*

fun Routing.talerWebHandlers() {
    post("/accounts/{USERNAME}/withdrawals") {
        val c = call.myAuth(TokenScope.readwrite) ?: throw unauthorized()
        // Admin not allowed to withdraw in the name of customers:
        val accountName = call.expectUriComponent("USERNAME")
        if (c.login != accountName)
            throw unauthorized("User ${c.login} not allowed to withdraw for account '${accountName}'")
        val req = call.receive<BankAccountCreateWithdrawalRequest>()
        // Checking that the user has enough funds.
        val b = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        val withdrawalAmount = parseTalerAmount(req.amount)
        if (
            !isBalanceEnough(
                balance = b.expectBalance(),
                due = withdrawalAmount,
                maxDebt = b.maxDebt,
                hasBalanceDebt = b.hasDebt
            ))
            throw forbidden(
                hint = "Insufficient funds to withdraw with Taler",
                talerErrorCode = TalerErrorCode.TALER_EC_NONE // FIXME: need EC.
            )
        // Auth and funds passed, create the operation now!
        val opId = UUID.randomUUID()
        if(
            !db.talerWithdrawalCreate(
                opId,
                b.expectRowId(),
                withdrawalAmount
            )
        )
            throw internalServerError("Bank failed at creating the withdraw operation.")

        val bankBaseUrl = call.request.getBaseUrl()
            ?: throw internalServerError("Bank could not find its own base URL")
        call.respond(BankAccountCreateWithdrawalResponse(
            withdrawal_id = opId.toString(),
            taler_withdraw_uri = getTalerWithdrawUri(bankBaseUrl, opId.toString())
        ))
        return@post
    }
    get("/accounts/{USERNAME}/withdrawals/{W_ID}") {
        val c = call.myAuth(TokenScope.readonly) ?: throw unauthorized()
        val accountName = call.expectUriComponent("USERNAME")
        // Admin allowed to see the details
        if (c.login != accountName && c.login != "admin") throw forbidden()
        // Permissions passed, get the information.
        val opIdParam: String = call.request.queryParameters.get("W_ID") ?: throw
                MissingRequestParameterException("withdrawal_id")
        val opId = try {
            UUID.fromString(opIdParam)
        } catch (e: Exception) {
            logger.error(e.message)
            throw badRequest("withdrawal_id query parameter was malformed")
        }
        val op = db.talerWithdrawalGet(opId)
            ?: throw notFound(
                hint = "Withdrawal operation ${opIdParam} not found",
                talerEc = TalerErrorCode.TALER_EC_END
            )
        call.respond(BankAccountGetWithdrawalResponse(
            amount = op.amount.toString(),
            aborted = op.aborted,
            confirmation_done = op.confirmationDone,
            selection_done = op.selectionDone,
            selected_exchange_account = op.selectedExchangePayto,
            selected_reserve_pub = if (op.reservePub != null) {
                Base32Crockford.encode(op.reservePub)
            } else null
        ))
        return@get
    }
    post("/accounts/{USERNAME}/withdrawals/abort") {
        throw NotImplementedError()
    }
    post("/accounts/{USERNAME}/withdrawals/confirm") {
        throw NotImplementedError()
    }
}

