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
import kotlin.math.abs

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")

fun Routing.talerWireGatewayHandlers(db: Database, ctx: BankApplicationContext) {
    /** Authenticate and check access rights */
    suspend fun ApplicationCall.authCheck(scope: TokenScope, withAdmin: Boolean): String {
        val authCustomer = authenticateBankRequest(db, scope) ?: throw unauthorized()
        val username = getResourceName("USERNAME")
        if (!username.canI(authCustomer, withAdmin)) throw forbidden()
        return username
    }

    /** Retrieve the bank account for the selected username*/
    suspend fun ApplicationCall.bankAccount(): BankAccount {
        val username = getResourceName("USERNAME")
        val customer = db.customerGetFromLogin(username) ?: throw notFound(
            hint = "Customer $username not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
        )
        val bankAccount = db.bankAccountGetFromOwnerId(customer.expectRowId())
        ?: throw internalServerError("Exchange does not have a bank account")
        return bankAccount
    }

    get("/taler-wire-gateway/config") {
        call.respond(TWGConfigResponse(currency = ctx.currency))
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        call.authCheck(TokenScope.readwrite, true)
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
        val exchangeBankAccount = call.bankAccount()
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

    suspend fun <T> historyEndpoint(call: ApplicationCall, direction: TransactionDirection, reduce: (List<T>, String) -> Any, map: (BankAccountTransaction) -> T?) {
        call.authCheck(TokenScope.readonly, true)
        val params = getHistoryParams(call.request)
        val bankAccount = call.bankAccount()
        if (!bankAccount.isTalerExchange) throw forbidden("History is not related to a Taler exchange.")
        
        var start = params.start
        var delta = params.delta
        val items = mutableListOf<T>()
        
        while (delta != 0L) {
            val history = db.bankTransactionGetHistory(
                start = start,
                delta = delta,
                bankAccountId = bankAccount.expectRowId(),
                withDirection = direction
            )
            if (history.isEmpty())
                break; // TODO long polling here
            history.forEach {
                val item = map(it);
                // Advance cursor
                start = it.expectRowId()
    
                if (item != null) {
                    items.add(item)
                    // Reduce delta
                    if (delta < 0) delta++ else delta--;
                }
            }
        }
    
        if (items.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(reduce(items, bankAccount.internalPaytoUri))
        }
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        historyEndpoint(call, TransactionDirection.credit, ::IncomingHistory) {
            try {
                val reservePub = EddsaPublicKey(it.subject)
                IncomingReserveTransaction(
                    row_id = it.expectRowId(),
                    amount = it.amount,
                    date = TalerProtocolTimestamp(it.transactionDate),
                    debit_account = it.debtorPaytoUri,
                    reserve_pub = reservePub
                )
            } catch (e: Exception) {
                // This should usually not happen in the first place,
                // because transactions to the exchange without a valid
                // reserve pub should be bounced.
                logger.warn("Invalid incoming transaction ${it.expectRowId()}: ${it.subject}")
                null
            }
        }
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/outgoing") {
        historyEndpoint(call, TransactionDirection.debit, ::OutgoingHistory) {
            try {
                val split = it.subject.split(" ")
                OutgoingTransaction(
                    row_id = it.expectRowId(),
                    date = TalerProtocolTimestamp(it.transactionDate),
                    amount = it.amount,
                    credit_account = it.creditorPaytoUri,
                    wtid = ShortHashCode(split[0]),
                    exchange_base_url = split[1]
                )
            } catch (e: Exception) {
                // This should usually not happen in the first place,
                // because transactions from the exchange should be well formed
                logger.warn("Invalid outgoing transaction ${it.expectRowId()}: ${it.subject}")
                null
            }
        }
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
         call.authCheck(TokenScope.readwrite, false);
        val req = call.receive<AddIncomingRequest>()
        val internalCurrency = ctx.currency
        if (req.amount.currency != internalCurrency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        if (db.bankTransactionCheckExists(req.reserve_pub.encoded) != null)
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
        val exchangeAccount = call.bankAccount()
        val txTimestamp = Instant.now()
        val op = BankInternalTransaction(
            debtorAccountId = walletAccount.expectRowId(),
            amount = req.amount,
            creditorAccountId = exchangeAccount.expectRowId(),
            transactionDate = txTimestamp,
            subject = req.reserve_pub.encoded
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
        val rowId = db.bankTransactionCheckExists(req.reserve_pub.encoded)
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