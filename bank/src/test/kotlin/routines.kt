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

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import tech.libeufin.bank.BankAccountCreateWithdrawalResponse
import tech.libeufin.bank.WithdrawalStatus
import tech.libeufin.common.*
import tech.libeufin.common.test.*
import kotlin.test.assertEquals

// Test endpoint is correctly authenticated 
suspend fun ApplicationTestBuilder.authRoutine(
    method: HttpMethod, 
    path: String, 
    body: JsonObject? = null, 
    requireExchange: Boolean = false, 
    requireAdmin: Boolean = false,
    allowAdmin: Boolean = false
) {
    // No body when authentication must happen before parsing the body

    // No header
    client.request(path) {
        this.method = method
    }.assertUnauthorized(TalerErrorCode.GENERIC_PARAMETER_MISSING)

    // Bad header
    client.request(path) {
        this.method = method
        headers["Authorization"] = "WTF"
    }.assertBadRequest(TalerErrorCode.GENERIC_HTTP_HEADERS_MALFORMED)
    
    // Unknown account
    client.request(path) {
        this.method = method
        basicAuth("unknown", "password")
    }.assertUnauthorized()

    // Wrong password
    client.request(path) {
        this.method = method
        basicAuth("merchant", "wrong-password")
    }.assertUnauthorized()

    // Wrong account
    client.request(path) {
        this.method = method
        basicAuth("exchange", "merchant-password")
    }.assertUnauthorized()

    if (requireAdmin) {
        // Not exchange account
        client.request(path) {
            this.method = method
            pwAuth("merchant")
        }.assertUnauthorized()
    } else if (!allowAdmin) {
        // Check no admin
        client.request(path) {
            this.method = method
            pwAuth("admin")
        }.assertUnauthorized()
    }

    if (requireExchange) {
        // Not exchange account
        client.request(path) {
            this.method = method
            if (body != null) json(body)
            pwAuth(if (requireAdmin) "admin" else "merchant")
        }.assertConflict(TalerErrorCode.BANK_ACCOUNT_IS_NOT_EXCHANGE)
    }
}

suspend inline fun <reified B> ApplicationTestBuilder.historyRoutine(
    url: String,
    crossinline ids: (B) -> List<Long>,
    registered: List<suspend () -> Unit>,
    ignored: List<suspend () -> Unit> = listOf(),
    polling: Boolean = true,
    auth: String? = null
) {
    abstractHistoryRoutine(ids, registered, ignored, polling) { params: String ->
        client.get("$url?$params") {
            pwAuth(auth)
        }
    }
}

suspend inline fun <reified B> ApplicationTestBuilder.statusRoutine(
    url: String,
    crossinline status: (B) -> WithdrawalStatus
) {
    val amount = TalerAmount("KUDOS:9.0")
    client.postA("/accounts/customer/withdrawals") {
        json { "amount" to amount } 
    }.assertOkJson<BankAccountCreateWithdrawalResponse> { resp ->
        val aborted_uuid = resp.taler_withdraw_uri.split("/").last()
        val confirmed_uuid = client.postA("/accounts/customer/withdrawals") {
            json { "amount" to amount } 
        }.assertOkJson<BankAccountCreateWithdrawalResponse>()
            .taler_withdraw_uri.split("/").last()

        // Check no useless polling
        assertTime(0, 100) {
            client.get("$url/$confirmed_uuid?long_poll_ms=1000&old_state=selected")
                .assertOkJson<B> { assertEquals(WithdrawalStatus.pending, status(it)) }
        }

        // Polling selected
        coroutineScope {
            launch {  // Check polling succeed
                assertTime(100, 200) {
                    client.get("$url/$confirmed_uuid?long_poll_ms=1000")
                        .assertOkJson<B> { assertEquals(WithdrawalStatus.selected, status(it)) }
                }
            }
            launch {  // Check polling succeed
                assertTime(100, 200) {
                    client.get("$url/$aborted_uuid?long_poll_ms=1000")
                        .assertOkJson<B> { assertEquals(WithdrawalStatus.selected, status(it)) }
                }
            }
            delay(100)
            withdrawalSelect(confirmed_uuid)
            withdrawalSelect(aborted_uuid)
        }
       
        // Polling confirmed
        coroutineScope {
            launch {  // Check polling succeed
                assertTime(100, 200) {
                    client.get("$url/$confirmed_uuid?long_poll_ms=1000&old_state=selected")
                        .assertOkJson<B> {  assertEquals(WithdrawalStatus.confirmed, status(it))}
                }
            }
            launch {  // Check polling timeout
                assertTime(200, 300) {
                    client.get("$url/$aborted_uuid?long_poll_ms=200&old_state=selected")
                        .assertOkJson<B> {  assertEquals(WithdrawalStatus.selected, status(it)) }
                }
            }
            delay(100)
            client.postA("/accounts/customer/withdrawals/$confirmed_uuid/confirm").assertNoContent()
        }

        // Polling abort
        coroutineScope {
            launch {
                assertTime(200, 300) {
                    client.get("$url/$confirmed_uuid?long_poll_ms=200&old_state=confirmed")
                        .assertOkJson<B> { assertEquals(WithdrawalStatus.confirmed, status(it))}
                }
            }
            launch {
                assertTime(100, 200) {
                    client.get("$url/$aborted_uuid?long_poll_ms=1000&old_state=selected")
                        .assertOkJson<B> { assertEquals(WithdrawalStatus.aborted, status(it)) }
                }
            }
            delay(100)
            client.post("/taler-integration/withdrawal-operation/$aborted_uuid/abort").assertNoContent()
        }
    }
}