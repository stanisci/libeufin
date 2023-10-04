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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.extractReservePubFromSubject
import tech.libeufin.util.stripIbanPayto
import java.time.Instant

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun Routing.talerWireGatewayHandlers(db: Database, ctx: BankApplicationContext) {
    get("/taler-wire-gateway/config") {
        call.respond(TWGConfigResponse(currency = ctx.currency))
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = false)) throw forbidden()
        val req = call.receive<TransferRequest>()
        // Checking for idempotency.
        val maybeDoneAlready = db.talerTransferGetFromUid(req.request_uid.encoded)
        val creditAccount = stripIbanPayto(req.credit_account)
        if (maybeDoneAlready != null) {
            val isIdempotent =
                maybeDoneAlready.amount == req.amount
                        && maybeDoneAlready.creditAccount == creditAccount
                        && maybeDoneAlready.exchangeBaseUrl == req.exchange_base_url
                        && maybeDoneAlready.wtid == req.wtid.encoded
            if (isIdempotent) {
                call.respond(
                    TransferResponse(
                        timestamp = TalerProtocolTimestamp.fromMicroseconds(maybeDoneAlready.timestamp),
                        row_id = maybeDoneAlready.debitTxRowId
                    )
                )
                return@post
            }
            throw conflict(
                hint = "request_uid used already",
                talerEc = TalerErrorCode.TALER_EC_BANK_TRANSFER_REQUEST_UID_REUSED
            )
        }
        // Legitimate request, go on.
        val internalCurrency = ctx.currency
        if (internalCurrency != req.amount.currency)
            throw badRequest("Currency mismatch: $internalCurrency vs ${req.amount.currency}")
        val exchangeBankAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("Exchange does not have a bank account")
        val transferTimestamp = Instant.now()
        val dbRes = db.talerTransferCreate(
            req = req,
            exchangeBankAccountId = exchangeBankAccount.expectRowId(),
            timestamp = transferTimestamp
        )
        if (dbRes.txResult == BankTransactionResult.CONFLICT)
            throw conflict(
                "Insufficient balance for exchange",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
        if (dbRes.txResult == BankTransactionResult.NO_CREDITOR)
            throw notFound(
                "Creditor account was not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
        val debitRowId = dbRes.txRowId
            ?: throw internalServerError("Database did not return the debit tx row ID")
        call.respond(
            TransferResponse(
                timestamp = TalerProtocolTimestamp(transferTimestamp),
                row_id = debitRowId
            )
        )
        return@post
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        val c = call.authenticateBankRequest(db, TokenScope.readonly) ?: throw unauthorized()
        val accountName = call.getResourceName("USERNAME")
        if (!accountName.canI(c, withAdmin = true)) throw forbidden()
        val params = getHistoryParams(call.request)
        val accountCustomer = db.customerGetFromLogin(accountName) ?: throw notFound(
            hint = "Customer $accountName not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
        )
        val bankAccount = db.bankAccountGetFromOwnerId(accountCustomer.expectRowId())
            ?: throw internalServerError("Customer '$accountName' lacks bank account.")
        if (!bankAccount.isTalerExchange) throw forbidden("History is not related to a Taler exchange.")

        val history: List<BankAccountTransaction> = db.bankTransactionGetHistory(
            start = params.start,
            delta = params.delta,
            bankAccountId = bankAccount.expectRowId(),
            withDirection = TransactionDirection.credit
        )
        if (history.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
            return@get
        }
        val resp = IncomingHistory(credit_account = bankAccount.internalPaytoUri)
        history.forEach {
            val reservePub = extractReservePubFromSubject(it.subject)
            if (reservePub == null) {
                // This should usually not happen in the first place,
                // because transactions to the exchange without a valid
                // reserve pub should be bounced.
                logger.warn("exchange account ${c.login} contains invalid incoming transaction ${it.expectRowId()}")
            } else {
                resp.incoming_transactions.add(
                    IncomingReserveTransaction(
                        row_id = it.expectRowId(),
                        amount = it.amount,
                        date = TalerProtocolTimestamp(it.transactionDate),
                        debit_account = it.debtorPaytoUri,
                        reserve_pub = reservePub
                    )
                )
            }
        }
        call.respond(resp)
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
        val c = call.authenticateBankRequest(db, TokenScope.readwrite) ?: throw unauthorized()
        if (!call.getResourceName("USERNAME").canI(c, withAdmin = false)) throw forbidden()
        val req = call.receive<AddIncomingRequest>()
        val internalCurrency = ctx.currency
        if (req.amount.currency != internalCurrency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        if (db.bankTransactionCheckExists(req.reserve_pub) != null)
            throw conflict(
                "Reserve pub. already used",
                TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
        val strippedIbanPayto: String = stripIbanPayto(req.debit_account) ?: throw badRequest("Invalid debit_account payto URI")
        val walletAccount = db.bankAccountGetFromInternalPayto(strippedIbanPayto)
            ?: throw notFound(
                "debit_account not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
        val exchangeAccount = db.bankAccountGetFromOwnerId(c.expectRowId())
            ?: throw internalServerError("exchange bank account not found, despite it's a customer")
        val txTimestamp = Instant.now()
        val op = BankInternalTransaction(
            debtorAccountId = walletAccount.expectRowId(),
            amount = req.amount,
            creditorAccountId = exchangeAccount.expectRowId(),
            transactionDate = txTimestamp,
            subject = req.reserve_pub
        )
        val res = db.bankTransactionCreate(op)
        /**
         * Other possible errors are highly unlikely, because of the
         * previous checks on the existence of the involved bank accounts.
         */
        if (res == BankTransactionResult.CONFLICT)
            throw conflict(
                "Insufficient balance",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
        val rowId = db.bankTransactionCheckExists(req.reserve_pub)
            ?: throw internalServerError("Could not find the just inserted bank transaction")
        call.respond(
            AddIncomingResponse(
                row_id = rowId,
                timestamp = TalerProtocolTimestamp(txTimestamp)
            )
        )
        return@post
    }
}