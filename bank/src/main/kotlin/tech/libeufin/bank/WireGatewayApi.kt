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

fun Routing.wireGatewayApi(db: Database, ctx: BankApplicationContext) {
    get("/taler-wire-gateway/config") {
        call.respond(TWGConfigResponse(currency = ctx.currency))
        return@get
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite)
        val req = call.receive<TransferRequest>()
        if (req.amount.currency != ctx.currency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        val dbRes = db.talerTransferCreate(
            req = req,
            username = login,
            timestamp = Instant.now()
        )
        when (dbRes.txResult) {
            TalerTransferResult.NO_DEBITOR -> throw notFound(
                "Customer $login not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerTransferResult.NOT_EXCHANGE -> throw conflict(
                "$login is not an exchange account.",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerTransferResult.NO_CREDITOR -> throw notFound(
                "Creditor account was not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerTransferResult.SAME_ACCOUNT -> throw conflict(
                "Wire transfer attempted with credit and debit party being the same bank account",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            TalerTransferResult.BOTH_EXCHANGE -> throw conflict(
                "Wire transfer attempted with credit and debit party being both exchange account",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            TalerTransferResult.REQUEST_UID_REUSE -> throw conflict(
                "request_uid used already",
                TalerErrorCode.TALER_EC_BANK_TRANSFER_REQUEST_UID_REUSED
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
        val (login, _) = call.authCheck(db, TokenScope.readonly)
        val params = getHistoryParams(call.request.queryParameters)
        val bankAccount = call.bankAccount(db)
        
        if (!bankAccount.isTalerExchange)
            throw conflict(
                "$login is not an exchange account.",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )

        val items = db.dbLambda(params, bankAccount.bankAccountId!!);
    
        if (items.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(reduce(items, bankAccount.internalPaytoUri.canonical))
        }
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
        historyEndpoint(call, ::IncomingHistory, Database::exchangeIncomingPoolHistory)
    }

    get("/accounts/{USERNAME}/taler-wire-gateway/history/outgoing") {
        historyEndpoint(call, ::OutgoingHistory, Database::exchangeOutgoingPoolHistory)
    }

    post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
        val (login, _) = call.authCheck(db, TokenScope.readwrite) // TODO authAdmin ?
        val req = call.receive<AddIncomingRequest>()
        if (req.amount.currency != ctx.currency)
            throw badRequest(
                "Currency mismatch",
                TalerErrorCode.TALER_EC_GENERIC_CURRENCY_MISMATCH
            )
        val timestamp = Instant.now()
        val dbRes = db.talerAddIncomingCreate(
            req = req,
            username = login,
            timestamp = timestamp
        )
        when (dbRes.txResult) {
            TalerAddIncomingResult.NO_CREDITOR -> throw notFound(
                "Customer $login not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerAddIncomingResult.NOT_EXCHANGE -> throw conflict(
                "$login is not an exchange account.",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerAddIncomingResult.NO_DEBITOR -> throw notFound(
                "Debitor account was not found",
                TalerErrorCode.TALER_EC_BANK_UNKNOWN_ACCOUNT
            )
            TalerAddIncomingResult.SAME_ACCOUNT -> throw conflict(
                "Wire transfer attempted with credit and debit party being the same bank account",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            TalerAddIncomingResult.BOTH_EXCHANGE -> throw conflict(
                "Wire transfer attempted with credit and debit party being both exchange account",
                TalerErrorCode.TALER_EC_BANK_SAME_ACCOUNT
            )
            TalerAddIncomingResult.RESERVE_PUB_REUSE -> throw conflict(
                "reserve_pub used already",
                TalerErrorCode.TALER_EC_BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
            TalerAddIncomingResult.BALANCE_INSUFFICIENT -> throw conflict(
                "Insufficient balance for debitor",
                TalerErrorCode.TALER_EC_BANK_UNALLOWED_DEBIT
            )
            TalerAddIncomingResult.SUCCESS -> call.respond(
                AddIncomingResponse(
                    timestamp = TalerProtocolTimestamp(timestamp),
                    row_id = dbRes.txRowId!!
                )
            )
        }
    }
}