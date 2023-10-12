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

    /** Retrieve the bank account info for the selected username*/
    suspend fun ApplicationCall.bankAccount(): Database.BankInfo {
        val username = getResourceName("USERNAME")
        return db.bankAccountInfoFromCustomerLogin(username) ?: throw notFound(
            hint = "Customer $username not found",
            talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
        )
    }

    get("/taler-wire-gateway/config") {
        call.respond(TWGConfigResponse(currency = ctx.currency))
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        val username = call.authCheck(TokenScope.readwrite, true)
        val req = call.receive<TransferRequest>()
        if (req.amount.currency != ctx.currency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        val dbRes = db.talerTransferCreate(
            req = req,
            username = username,
            timestamp = Instant.now()
        )
        when (dbRes.txResult) {
            TalerTransferResult.NO_DEBITOR -> 
                throw notFound(
                    hint = "Customer $username not found",
                    talerEc = TalerErrorCode.TALER_EC_END // FIXME: need EC.
                )
            TalerTransferResult.NOT_EXCHANGE -> 
                throw forbidden("$username is not an exchange account.")
            TalerTransferResult.NO_CREDITOR -> throw notFound(
                "Creditor account was not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerTransferResult.REQUEST_UID_REUSE -> throw conflict(
                hint = "request_uid used already",
                talerEc = TalerErrorCode.TALER_EC_BANK_TRANSFER_REQUEST_UID_REUSED
            )
            TalerTransferResult.BALANCE_INSUFFICIENT -> throw conflict(
                "Insufficient balance for exchange",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            TalerTransferResult.SUCCESS -> call.respond(
                TransferResponse(
                    timestamp = dbRes.timestamp!!,
                    row_id = dbRes.txRowId!!
                )
            )
        }
    }

    suspend fun <T> historyEndpoint(
        call: ApplicationCall, 
        reduce: (List<T>, String) -> Any, 
        dbLambda: suspend Database.(HistoryParams, Long) -> List<T>
    ) {
        call.authCheck(TokenScope.readonly, true)
        val params = getHistoryParams(call.request.queryParameters)
        val bankAccount = call.bankAccount()
        if (!bankAccount.isTalerExchange) throw forbidden("History is not related to a Taler exchange.")

        val items = db.dbLambda(params, bankAccount.id);
    
        if (items.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(reduce(items, bankAccount.internalPaytoUri))
        }
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        historyEndpoint(call, ::IncomingHistory, Database::exchangeIncomingPoolHistory)
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/outgoing") {
        historyEndpoint(call, ::OutgoingHistory, Database::exchangeOutgoingPoolHistory)
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
        call.authCheck(TokenScope.readwrite, false);
        val req = call.receive<AddIncomingRequest>()
        if (req.amount.currency != ctx.currency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        
        // TODO check conflict in transaction
        if (db.bankTransactionCheckExists(req.reserve_pub.encoded()) != null)
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
        if (!exchangeAccount.isTalerExchange) throw forbidden("Expected taler exchange bank account.")

        val txTimestamp = Instant.now()
        val op = BankInternalTransaction(
            debtorAccountId = walletAccount.expectRowId(),
            amount = req.amount,
            creditorAccountId = exchangeAccount.id,
            transactionDate = txTimestamp,
            subject = req.reserve_pub.encoded()
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
        val rowId = db.bankTransactionCheckExists(req.reserve_pub.encoded())
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