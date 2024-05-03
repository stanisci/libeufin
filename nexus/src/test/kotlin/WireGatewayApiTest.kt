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

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Test
import tech.libeufin.common.*
import tech.libeufin.nexus.*

class WireGatewayApiTest {
    // GET /taler-wire-gateway/config
    @Test
    fun config() = serverSetup { _ ->
        authRoutine(HttpMethod.Get, "/taler-wire-gateway/config")

        client.getA("/taler-wire-gateway/config").assertOk()
    }

    // POST /taler-wire-gateway/transfer
    @Test
    fun transfer() = serverSetup { _ -> 
        val valid_req = obj {
            "request_uid" to HashCode.rand()
            "amount" to "CHF:55"
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to ShortHashCode.rand()
            "credit_account" to grothoffPayto
        }

        authRoutine(HttpMethod.Post, "/taler-wire-gateway/transfer")

        // Check OK
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // check idempotency
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // Trigger conflict due to reused request_uid
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to ShortHashCode.rand()
                "exchange_base_url" to "http://different-exchange.example.com/"
            }
        }.assertConflict(TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED)

        // Currency mismatch
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) {
                "amount" to "EUR:33"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Bad BASE32 wtid
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len wtid
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to Base32Crockford.encode(ByteArray(31).rand())
            }
        }.assertBadRequest()

        // Bad BASE32 request_uid
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to "I love chocolate"
            }
        }.assertBadRequest()

        // Bad BASE32 len wtid
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to Base32Crockford.encode(ByteArray(65).rand())
            }
        }.assertBadRequest()

        // Bad payto kind
        client.postA("/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "credit_account" to "payto://x-taler-bank/bank.hostname.test/bar"
            }
        }.assertBadRequest()
    }
    
    // GET /taler-wire-gateway/history/incoming
    @Test
    fun historyIncoming() = serverSetup { db ->
        authRoutine(HttpMethod.Get, "/taler-wire-gateway/history/incoming")
        historyRoutine<IncomingHistory>(
            url = "/taler-wire-gateway/history/incoming",
            ids = { it.incoming_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    client.postA("/taler-wire-gateway/admin/add-incoming") {
                        json {
                            "amount" to "CHF:12"
                            "reserve_pub" to EddsaPublicKey.rand()
                            "debit_account" to grothoffPayto
                        }
                    }.assertOk()
                },
                { 
                    // Transactions using raw bank transaction logic
                    talerableIn(db)
                }
            ),
            ignored = listOf(
                {
                    // Ignore malformed incoming transaction
                    ingestIncomingPayment(db, genInPay("ignored"))
                },
                {
                    // Ignore outgoing transaction
                    talerableOut(db)
                }
            )
        )
    }

    // GET /taler-wire-gateway/history/outgoing
    @Test
    fun historyOutgoing() = serverSetup { db ->
        authRoutine(HttpMethod.Get, "/taler-wire-gateway/history/outgoing")
        historyRoutine<OutgoingHistory>(
            url = "/taler-wire-gateway/history/outgoing",
            ids = { it.outgoing_transactions.map { it.row_id } },
            registered = listOf(
                {
                    talerableOut(db)
                }
            ),
            ignored = listOf(
                { 
                    // Ignore pending transfers
                    transfer()
                },
                { 
                    // Ignore manual incoming transaction
                    talerableIn(db)
                },
                {
                    // Ignore malformed incoming transaction
                    ingestIncomingPayment(db, genInPay("ignored"))
                },
                {
                    // Ignore malformed outgoing transaction
                    ingestOutgoingPayment(db, genOutPay("ignored"))
                }
            )
        )
    }

    // POST /taler-wire-gateway/admin/add-incoming
    @Test
    fun addIncoming() = serverSetup { _ -> 
        val valid_req = obj {
            "amount" to "CHF:44"
            "reserve_pub" to EddsaPublicKey.rand()
            "debit_account" to grothoffPayto
        }

        authRoutine(HttpMethod.Post, "/taler-wire-gateway/admin/add-incoming")

        // Check OK
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertOk()

        // Trigger conflict due to reused reserve_pub
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT)

        // Currency mismatch
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { "amount" to "EUR:33" }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Bad BASE32 reserve_pub
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len reserve_pub
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to Base32Crockford.encode(ByteArray(31).rand())
            }
        }.assertBadRequest()

        // Bad payto kind
        client.postA("/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "debit_account" to "payto://x-taler-bank/bank.hostname.test/bar"
            }
        }.assertBadRequest()
    }

    @Test
    fun noApi() = serverSetup("mini.conf") { _ ->
        client.get("/taler-wire-gateway/config").assertNotImplemented()
    }
}