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
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.bank.db.*
import tech.libeufin.common.*

class BankIntegrationApiTest {
    // GET /taler-integration/config
    @Test
    fun config() = bankSetup { _ ->
        client.get("/taler-integration/config").assertOk()
    }

    // GET /taler-integration/withdrawal-operation/UUID
    @Test
    fun get() = bankSetup { _ ->
        val amount = TalerAmount("KUDOS:9.0")
        // Check OK
        client.postA("/accounts/customer/withdrawals") {
            json { "amount" to amount } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> { resp ->
            val uuid = resp.taler_withdraw_uri.split("/").last()
            client.get("/taler-integration/withdrawal-operation/$uuid")
                .assertOkJson<BankWithdrawalOperationStatus> {
                assert(!it.selection_done)
                assert(!it.aborted)
                assert(!it.transfer_done)
                assertEquals(amount, it.amount)
                assertEquals(listOf("iban"), it.wire_types)
                assertEquals(amount, it.amount)
            }
        }

        // Check polling
        statusRoutine<BankWithdrawalOperationStatus>("/taler-integration/withdrawal-operation") { it.status }

        // Check unknown
        client.get("/taler-integration/withdrawal-operation/${UUID.randomUUID()}")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
        
        // Check bad UUID
        client.get("/taler-integration/withdrawal-operation/chocolate")
            .assertBadRequest()
    }

    // POST /taler-integration/withdrawal-operation/UUID
    @Test
    fun select() = bankSetup { _ ->
        val reserve_pub = randEddsaPublicKey()
        val req = obj {
            "reserve_pub" to reserve_pub
            "selected_exchange" to exchangePayto.canonical
        }

        // Check bad UUID
        client.post("/taler-integration/withdrawal-operation/chocolate") {
            json(req)
        }.assertBadRequest()

        // Check unknown
        client.post("/taler-integration/withdrawal-operation/${UUID.randomUUID()}") {
            json(req)
        }.assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)

        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check OK
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertOkJson<BankWithdrawalOperationPostResponse> {
                assertEquals(WithdrawalStatus.selected, it.status)
            }
            // Check idempotence
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertOkJson<BankWithdrawalOperationPostResponse> {
                assertEquals(WithdrawalStatus.selected, it.status)
            }
            // Check already selected
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req) {
                    "reserve_pub" to randEddsaPublicKey()
                }
            }.assertConflict(TalerErrorCode.BANK_WITHDRAWAL_OPERATION_RESERVE_SELECTION_CONFLICT)
        }   

        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check reserve_pub_reuse
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json(req)
            }.assertConflict(TalerErrorCode.BANK_DUPLICATE_RESERVE_PUB_SUBJECT)
            // Check unknown account
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to unknownPayto
                }
            }.assertConflict(TalerErrorCode.BANK_UNKNOWN_ACCOUNT)
            // Check account not exchange
            client.post("/taler-integration/withdrawal-operation/$uuid") {
                json {
                    "reserve_pub" to randEddsaPublicKey()
                    "selected_exchange" to merchantPayto
                }
            }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE)
        }
    }

    // POST /taler-integration/withdrawal-operation/UUID/abort
    @Test
    fun abort() = bankSetup { _ ->
        // Check abort created
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()

            // Check OK
            client.postA("/taler-integration/withdrawal-operation/$uuid/abort").assertNoContent()
            // Check idempotence
            client.postA("/taler-integration/withdrawal-operation/$uuid/abort").assertNoContent()
        }

        // Check abort selected
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)

            // Check OK
            client.postA("/taler-integration/withdrawal-operation/$uuid/abort").assertNoContent()
            // Check idempotence
            client.postA("/taler-integration/withdrawal-operation/$uuid/abort").assertNoContent()
        }

        // Check abort confirmed
        client.postA("/accounts/merchant/withdrawals") {
            json { "amount" to "KUDOS:1" } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse> {
            val uuid = it.taler_withdraw_uri.split("/").last()
            withdrawalSelect(uuid)
            client.postA("/accounts/merchant/withdrawals/$uuid/confirm").assertNoContent()

            // Check error
            client.postA("/taler-integration/withdrawal-operation/$uuid/abort")
                .assertConflict(TalerErrorCode.BANK_ABORT_CONFIRM_CONFLICT)
        }

        // Check bad UUID
        client.postA("/taler-integration/withdrawal-operation/chocolate/abort").assertBadRequest()

        // Check unknown
        client.postA("/taler-integration/withdrawal-operation/${UUID.randomUUID()}/abort")
            .assertNotFound(TalerErrorCode.BANK_TRANSACTION_NOT_FOUND)
    }

    // Testing the generation of taler://withdraw-URIs.
    @Test
    fun testWithdrawUri() {
        // Checking the taler+http://-style.
        val withHttp = getTalerWithdrawUri(
            "http://example.com",
            "my-id"
        )
        assertEquals(withHttp, "taler+http://withdraw/example.com/taler-integration/my-id")
        // Checking the taler://-style
        val onlyTaler = getTalerWithdrawUri(
            "https://example.com/",
            "my-id"
        )
        // Note: this tests as well that no double slashes belong to the result
        assertEquals(onlyTaler, "taler://withdraw/example.com/taler-integration/my-id")
        // Checking the removal of subsequent slashes
        val manySlashes = getTalerWithdrawUri(
            "https://www.example.com//////",
            "my-id"
        )
        assertEquals(manySlashes, "taler://withdraw/www.example.com/taler-integration/my-id")
        // Checking with specified port number
        val withPort = getTalerWithdrawUri(
            "https://www.example.com:9876",
            "my-id"
        )
        assertEquals(withPort, "taler://withdraw/www.example.com:9876/taler-integration/my-id")
    }
}