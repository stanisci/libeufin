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

// This file contains the Taler Wire Gateway API handlers.

package tech.libeufin.bank

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.PipelineContext
import java.time.Instant
import tech.libeufin.common.*
import tech.libeufin.bank.db.*
import tech.libeufin.bank.db.ExchangeDAO.*
import tech.libeufin.bank.auth.*


fun Routing.wireGatewayApi(db: Database, ctx: BankConfig) {
    auth(db, TokenScope.readwrite) {
        get("/accounts/{USERNAME}/taler-wire-gateway/config") {
            call.respond(WireGatewayConfig(
                currency = ctx.regionalCurrency
            ))
        }
        post("/accounts/{USERNAME}/taler-wire-gateway/transfer") {
            val req = call.receive<TransferRequest>()
            ctx.checkRegionalCurrency(req.amount)
            val res = db.exchange.transfer(
                req = req,
                login = username,
                now = Instant.now()
            )
            when (res) {
                is TransferResult.UnknownExchange -> throw unknownAccount(username)
                is TransferResult.NotAnExchange -> throw conflict(
                    "$username is not an exchange account.",
                    TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE
                )
                is TransferResult.UnknownCreditor -> throw conflict(
                    "Creditor account was not found",
                    TalerErrorCode.BANK_UNKNOWN_CREDITOR
                )
                is TransferResult.BothPartyAreExchange -> throw conflict(
                    "Wire transfer attempted with credit and debit party being both exchange account",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                is TransferResult.ReserveUidReuse -> throw conflict(
                    "request_uid used already",
                    TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
                )
                is TransferResult.BalanceInsufficient -> throw conflict(
                    "Insufficient balance for exchange",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                is TransferResult.Success -> call.respond(
                    TransferResponse(
                        timestamp = res.timestamp,
                        row_id = res.id
                    )
                )
            }
        }
    }
    auth(db, TokenScope.readonly) {
        suspend fun <T> PipelineContext<Unit, ApplicationCall>.historyEndpoint(
            reduce: (List<T>, String) -> Any, 
            dbLambda: suspend ExchangeDAO.(HistoryParams, Long, BankPaytoCtx) -> List<T>
        ) {
            val params = HistoryParams.extract(context.request.queryParameters)
            val bankAccount = call.bankInfo(db, ctx.payto)
            
            if (!bankAccount.isTalerExchange)
                throw conflict(
                    "$username is not an exchange account.",
                    TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE
                )

            val items = db.exchange.dbLambda(params, bankAccount.bankAccountId, ctx.payto);
        
            if (items.isEmpty()) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(reduce(items, bankAccount.payto))
            }
        }
        get("/accounts/{USERNAME}/taler-wire-gateway/history/incoming") {
            historyEndpoint(::IncomingHistory, ExchangeDAO::incomingHistory)
        }
        get("/accounts/{USERNAME}/taler-wire-gateway/history/outgoing") {
            historyEndpoint(::OutgoingHistory, ExchangeDAO::outgoingHistory)
        }
    }
    authAdmin(db, TokenScope.readwrite) {
        post("/accounts/{USERNAME}/taler-wire-gateway/admin/add-incoming") {
            val req = call.receive<AddIncomingRequest>()
            ctx.checkRegionalCurrency(req.amount)
            val timestamp = Instant.now()
            val res = db.exchange.addIncoming(
                req = req,
                login = username,
                now = timestamp
            )
            when (res) {
                is AddIncomingResult.UnknownExchange -> throw unknownAccount(username)
                is AddIncomingResult.NotAnExchange -> throw conflict(
                    "$username is not an exchange account.",
                    TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE
                )
                is AddIncomingResult.UnknownDebtor -> throw conflict(
                    "Debtor account ${req.debit_account} was not found",
                    TalerErrorCode.BANK_UNKNOWN_DEBTOR
                )
                is AddIncomingResult.BothPartyAreExchange -> throw conflict(
                    "Wire transfer attempted with credit and debit party being both exchange account",
                    TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE
                )
                is AddIncomingResult.ReservePubReuse -> throw conflict(
                    "reserve_pub used already",
                    TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT
                )
                is AddIncomingResult.BalanceInsufficient -> throw conflict(
                    "Insufficient balance for debitor",
                    TalerErrorCode.BANK_UNALLOWED_DEBIT
                )
                is AddIncomingResult.Success -> call.respond(
                    AddIncomingResponse(
                        timestamp = TalerProtocolTimestamp(timestamp),
                        row_id = res.id
                    )
                )
            }
        }
    }
}