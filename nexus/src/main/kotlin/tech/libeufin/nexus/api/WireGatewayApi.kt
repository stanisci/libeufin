/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

package tech.libeufin.nexus.api

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import tech.libeufin.common.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.db.*
import tech.libeufin.nexus.db.PaymentDAO.*
import tech.libeufin.nexus.db.InitiatedDAO.*
import tech.libeufin.nexus.db.ExchangeDAO.*
import java.time.Instant


fun Routing.wireGatewayApi(db: Database, cfg: NexusConfig) = authApi(cfg.wireGatewayApiCfg) {
    get("/taler-wire-gateway/config") {
        call.respond(WireGatewayConfig(
            currency = cfg.currency
        ))
    }
    post("/taler-wire-gateway/transfer") {
        val req = call.receive<TransferRequest>()
        cfg.checkCurrency(req.amount)
        req.credit_account.expectRequestIban()
        val bankId = run {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            Base32Crockford.encode(bytes)
        }
        val res = db.exchange.transfer(
            req,
            bankId,
            Instant.now()
        )
        when (res) {
            TransferResult.RequestUidReuse -> throw conflict(
                "request_uid used already",
                TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED
            )
            is TransferResult.Success -> call.respond(
                TransferResponse(
                    timestamp = res.timestamp,
                    row_id = res.id
                )
            )
        }
    }
    suspend fun <T> PipelineContext<Unit, ApplicationCall>.historyEndpoint(
        reduce: (List<T>, String) -> Any, 
        dbLambda: suspend ExchangeDAO.(HistoryParams) -> List<T>
    ) {
        val params = HistoryParams.extract(context.request.queryParameters)
        val items = db.exchange.dbLambda(params)
        if (items.isEmpty()) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(reduce(items, cfg.payto))
        }
    }
    get("/taler-wire-gateway/history/incoming") {
        historyEndpoint(::IncomingHistory, ExchangeDAO::incomingHistory)
    }
    get("/taler-wire-gateway/history/outgoing") {
        historyEndpoint(::OutgoingHistory, ExchangeDAO::outgoingHistory)
    }
    post("/taler-wire-gateway/admin/add-incoming") {
        val req = call.receive<AddIncomingRequest>()
        cfg.checkCurrency(req.amount)
        req.debit_account.expectRequestIban()
        val timestamp = Instant.now()
        val bankId = run {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            Base32Crockford.encode(bytes)
        }
        val res = db.payment.registerTalerableIncoming(IncomingPayment(
            amount = req.amount,
            debitPaytoUri = req.debit_account.toString(),
            wireTransferSubject = "Manual incoming ${req.reserve_pub}",
            executionTime = Instant.now(),
            bankId = bankId
        ), req.reserve_pub)
        when (res) {
            IncomingRegistrationResult.ReservePubReuse -> throw conflict(
                "reserve_pub used already",
                TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT
            )
            is IncomingRegistrationResult.Success -> call.respond(
                AddIncomingResponse(
                    timestamp = TalerProtocolTimestamp(timestamp),
                    row_id = res.id
                )
            )
        }
    }
}