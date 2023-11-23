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

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.util.*

class WireGatewayApiTest {
    // Testing the POST /transfer call from the TWG API.
    @Test
    fun transfer() = bankSetup { _ -> 
        val valid_req = obj {
            "request_uid" to randHashCode()
            "amount" to "KUDOS:55"
            "exchange_base_url" to "http://exchange.example.com/"
            "wtid" to randShortHashCode()
            "credit_account" to merchantPayto
        };

        authRoutine(HttpMethod.Post, "/accounts/merchant/taler-wire-gateway/transfer", valid_req)

        // Checking exchange debt constraint.
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        setMaxDebt("exchange", TalerAmount("KUDOS:1000"))
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // check idempotency
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req)
        }.assertOk()

        // Trigger conflict due to reused request_uid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to randShortHashCode()
                "exchange_base_url" to "http://different-exchange.example.com/"
            }
        }.assertConflict(TalerErrorCode.BANK_TRANSFER_REQUEST_UID_REUSED)

        // Currency mismatch
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) {
                "amount" to "EUR:33"
            }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randHashCode()
                "wtid" to randShortHashCode()
                "credit_account" to unknownPayto
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_CREDITOR)

        // Same account
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randHashCode()
                "wtid" to randShortHashCode()
                "credit_account" to exchangePayto
            }
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Bad BASE32 wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "wtid" to randBase32Crockford(31)
            }
        }.assertBadRequest()

        // Bad BASE32 request_uid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to "I love chocolate"
            }
        }.assertBadRequest()

        // Bad BASE32 len wtid
        client.postA("/accounts/exchange/taler-wire-gateway/transfer") {
            json(valid_req) { 
                "request_uid" to randBase32Crockford(65)
            }
        }.assertBadRequest()
    }
    
    /**
     * Testing the /history/incoming call from the TWG API.
     */
    @Test
    fun historyIncoming() = bankSetup { 
        // Give Foo reasonable debt allowance:
        setMaxDebt("merchant", TalerAmount("KUDOS:1000"))
        authRoutine(HttpMethod.Get, "/accounts/merchant/taler-wire-gateway/history/incoming")
        historyRoutine<IncomingHistory>(
            url = "/accounts/exchange/taler-wire-gateway/history/incoming",
            ids = { it.incoming_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions using clean add incoming logic
                    addIncoming("KUDOS:10") 
                },
                { 
                    // Transactions using raw bank transaction logic
                    tx("merchant", "KUDOS:10", "exchange", IncomingTxMetadata(randShortHashCode()).encode())
                },
                {
                    // Transaction using withdraw logic
                    withdrawal("KUDOS:9")
                }
            ),
            ignored = listOf(
                {
                    // Ignore malformed incoming transaction
                    tx("merchant", "KUDOS:10", "exchange", "ignored")
                },
                {
                    // Ignore malformed outgoing transaction
                    tx("exchange", "KUDOS:10", "merchant", "ignored")
                }
            )
        )
    }

    
    /**
     * Testing the /history/outgoing call from the TWG API.
     */
    @Test
    fun historyOutgoing() = bankSetup {
        setMaxDebt("exchange", TalerAmount("KUDOS:1000000"))
        authRoutine(HttpMethod.Get, "/accounts/merchant/taler-wire-gateway/history/outgoing")
        historyRoutine<OutgoingHistory>(
            url = "/accounts/exchange/taler-wire-gateway/history/outgoing",
            ids = { it.outgoing_transactions.map { it.row_id } },
            registered = listOf(
                { 
                    // Transactions using clean add incoming logic
                    transfer("KUDOS:10")
                },
                { 
                    // Transactions using raw bank transaction logic
                    tx("exchange", "KUDOS:10", "merchant", OutgoingTxMetadata(randShortHashCode(), ExchangeUrl("http://exchange.example.com/")).encode())
                }
            ),
            ignored = listOf(
                {
                    // Ignore malformed incoming transaction
                    tx("merchant", "KUDOS:10", "exchange", "ignored")
                },
                {
                    // Ignore malformed outgoing transaction
                    tx("exchange", "KUDOS:10", "merchant", "ignored")
                }
            )
        )
    }

    // Testing the /admin/add-incoming call from the TWG API.
    @Test
    fun addIncoming() = bankSetup { _ -> 
        val valid_req = obj {
            "amount" to "KUDOS:44"
            "reserve_pub" to randEddsaPublicKey()
            "debit_account" to merchantPayto
        };

        authRoutine(HttpMethod.Post, "/accounts/merchant/taler-wire-gateway/admin/add-incoming", valid_req, requireAdmin = true)

        // Checking exchange debt constraint.
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_UNALLOWED_DEBIT)

        // Giving debt allowance and checking the OK case.
        setMaxDebt("merchant", TalerAmount("KUDOS:1000"))
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req, deflate = true)
        }.assertOk()

        // Trigger conflict due to reused reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req)
        }.assertConflict(TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT)

        // Currency mismatch
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { "amount" to "EUR:33" }
        }.assertBadRequest(TalerErrorCode.GENERIC_CURRENCY_MISMATCH)

        // Unknown account
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to unknownPayto
            }
        }.assertConflict(TalerErrorCode.BANK_UNKNOWN_DEBTOR)

        // Same account
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randEddsaPublicKey()
                "debit_account" to exchangePayto
            }
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_EXCHANGE)

        // Bad BASE32 reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to "I love chocolate"
            }
        }.assertBadRequest()
        
        // Bad BASE32 len reserve_pub
        client.postA("/accounts/exchange/taler-wire-gateway/admin/add-incoming") {
            json(valid_req) { 
                "reserve_pub" to randBase32Crockford(31)
            }
        }.assertBadRequest()
    }
}