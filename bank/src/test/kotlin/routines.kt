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

import tech.libeufin.bank.*
import tech.libeufin.util.*
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import net.taler.common.errorcodes.TalerErrorCode

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

// Test endpoint is correctly protected using 2fa 
suspend fun ApplicationTestBuilder.tanRoutine(
    username: String,
    prepare: suspend () -> Unit = {},
    routine: suspend (suspend HttpResponse.() -> HttpResponse) -> Unit,
) {
    // Check without 2FA
    prepare()
    client.patch("/accounts/$username") {
        pwAuth("admin")
        json {
            "tan_channel" to null as Int?
        }
    }.assertNoContent()
    routine({ this })

    // Check with 2FA
    prepare()
    client.patch("/accounts/$username") {
        pwAuth("admin")
        json { 
            "contact_data" to obj {
                "phone" to "+42"
            }
            "tan_channel" to "sms"
        }
    }.assertNoContent()
    routine({
        val id = this.assertAcceptedJson<TanChallenge>().challenge_id
        client.postA("/accounts/$username/challenge/$id").assertOk()
        client.postA("/accounts/$username/challenge/$id/confirm") {
            json { "tan" to smsCode("+42") }
        }
    })
}

inline suspend fun <reified B> ApplicationTestBuilder.historyRoutine(
    url: String,
    crossinline ids: (B) -> List<Long>,
    registered: List<suspend () -> Unit>,
    ignored: List<suspend () -> Unit> = listOf(),
    polling: Boolean = true,
    auth: String? = null
) {
    // Get history 
    val history: suspend (String) -> HttpResponse = { params: String ->
        client.get("$url?$params") {
            pwAuth(auth)
        }
    }
    // Check history is following specs
    val assertHistory: suspend HttpResponse.(Int) -> Unit = { size: Int ->
        assertHistoryIds<B>(size, ids)
    }
    // Get latest registered id
    val latestId: suspend () -> Long = {
        history("delta=-1").assertOkJson<B>().run { ids(this)[0] }
    }

    // Check error when no transactions
    history("delta=7").assertNoContent()

    // Run interleaved registered and ignore transactions
    val registered_iter = registered.iterator()
    val ignored_iter = ignored.iterator()
    while (registered_iter.hasNext() || ignored_iter.hasNext()) {
        if (registered_iter.hasNext()) registered_iter.next()()
        if (ignored_iter.hasNext()) ignored_iter.next()()
    }


    val nbRegistered = registered.size
    val nbIgnored = ignored.size
    val nbTotal = nbRegistered + nbIgnored

    // Check ignored
    history("delta=$nbTotal").assertHistory(nbRegistered)
    // Check skip ignored
    history("delta=$nbRegistered").assertHistory(nbRegistered)

    if (polling) {
        // Check no polling when we cannot have more transactions
        assertTime(0, 100) {
            history("delta=-${nbRegistered+1}&long_poll_ms=1000")
                .assertHistory(nbRegistered)
        }
        // Check no polling when already find transactions even if less than delta
        assertTime(0, 100) {
            history("delta=${nbRegistered+1}&long_poll_ms=1000")
                .assertHistory(nbRegistered)
        }

        // Check polling
        coroutineScope {
            val id = latestId()
            launch {  // Check polling succeed
                assertTime(100, 200) {
                    history("delta=2&start=$id&long_poll_ms=1000")
                        .assertHistory(1)
                }
            }
            launch {  // Check polling timeout
                assertTime(200, 300) {
                    history("delta=1&start=${id+nbTotal*3}&long_poll_ms=200")
                        .assertNoContent()
                }
            }
            delay(100)
            registered[0]()
        }

        // Test triggers
        for (register in registered) {
            coroutineScope {
                val id = latestId()
                launch {
                    assertTime(100, 200) {
                        history("delta=7&start=$id&long_poll_ms=1000") 
                            .assertHistory(1)
                    }
                }
                delay(100)
                register()
            }
        }

        // Test doesn't trigger
        coroutineScope {
            val id = latestId()
            launch {
                assertTime(200, 300) {
                    history("delta=7&start=$id&long_poll_ms=200") 
                        .assertNoContent()
                }
            }
            delay(100)
            for (ignore in ignored) {
                ignore()
            }
        }
    }

    // Testing ranges.
    repeat(20) {
        registered[0]()
    }
    val id = latestId()
    // Default
    history("").assertHistory(20)
    // forward range:
    history("delta=10").assertHistory(10)
    history("delta=10&start=4").assertHistory(10)
    // backward range:
    history("delta=-10").assertHistory(10)
    history("delta=-10&start=${id-4}").assertHistory(10)
}

inline suspend fun <reified B> ApplicationTestBuilder.statusRoutine(
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
            client.post("/withdrawals/$confirmed_uuid/confirm").assertNoContent()
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
            client.post("/withdrawals/$aborted_uuid/abort").assertNoContent()
        }
    }
}