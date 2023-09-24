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

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.taler.common.errorcodes.TalerErrorCode
import tech.libeufin.util.getNowUs

fun Routing.talerWireGatewayHandlers(db: Database, ctx: BankApplicationContext) {
    get("/taler-wire-gateway/config") {
        call.respond(TWGConfigResponse(currency = ctx.currency))
        return@get
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = true)) throw forbidden()
        val params = getHistoryParams(call.request)
        val bankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Customer '${c.login}' lacks bank account.")
        if (!bankAccount.isTalerExchange) throw forbidden("History is not related to a Taler exchange.")
        val bankAccountId = bankAccount.expectRowId()

        val history: List<BankAccountTransaction> = db.bankTransactionGetHistory(
            start = params.start,
            delta = params.delta,
            bankAccountId = bankAccountId,
            withDirection = TransactionDirection.credit
        )
        if (history.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        val resp = IncomingHistory(credit_account = bankAccount.internalPaytoUri)
        history.forEach {
            resp.incoming_transactions.add(IncomingReserveTransaction(
                row_id = it.expectRowId(),
                amount = it.amount.toString(),
                date = it.transactionDate,
                debit_account = it.debtorPaytoUri,
                reserve_pub = it.subject
            ))
        }
        call.respond(resp)
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = false)) throw forbidden()
        val req = call.receive<TransferRequest>()
        // Checking for idempotency.
        val maybeDoneAlready = db.talerTransferGetFromUid(req.request_uid)
        if (maybeDoneAlready != null) {
            val isIdempotent =
                maybeDoneAlready.amount == req.amount
                        && maybeDoneAlready.creditAccount == req.credit_account
                        && maybeDoneAlready.exchangeBaseUrl == req.exchange_base_url
                        && maybeDoneAlready.wtid == req.wtid
            if (isIdempotent) {
                call.respond(TransferResponse(
                    timestamp = maybeDoneAlready.timestamp,
                    row_id = maybeDoneAlready.debitTxRowId
                ))
                return@post
            }
            throw conflict(
                hint = "request_uid used already",
                talerEc = TalerErrorCode.TALER_EC_END // FIXME: need appropriate Taler EC.
            )
        }
        // Legitimate request, go on.
        val internalCurrency = ctx.currency
        if (internalCurrency != req.amount.currency)
            throw badRequest("Currency mismatch: $internalCurrency vs ${req.amount.currency}")
        val exchangeBankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Exchange does not have a bank account")
        val transferTimestamp = getNowUs()
        val dbRes = db.talerTransferCreate(
            req = req,
            exchangeBankAccountId = exchangeBankAccount.expectRowId(),
            timestamp = transferTimestamp
        )
        if (dbRes.txResult == Database.BankTransactionResult.CONFLICT)
            throw conflict(
                "Insufficient balance for exchange",
                TalerErrorCode.TALER_EC_END // FIXME
            )
        if (dbRes.txResult == Database.BankTransactionResult.NO_CREDITOR)
            throw notFound(
                "Creditor account was not found",
                TalerErrorCode.TALER_EC_END // FIXME
            )
        val debitRowId = dbRes.txRowId
            ?: throw internalServerError("Database did not return the debit tx row ID")
        call.respond(TransferResponse(
            timestamp = transferTimestamp,
            row_id = debitRowId
        ))
        return@post
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = false)) throw forbidden()
        val req = call.receive<AddIncomingRequest>()
        val amount = parseTalerAmount(req.amount)
        val internalCurrency = ctx.currency
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

