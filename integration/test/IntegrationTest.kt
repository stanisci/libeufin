/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

import org.junit.Test
import tech.libeufin.bank.*
import tech.libeufin.bank.Database as BankDb
import tech.libeufin.bank.TalerAmount as BankAmount
import tech.libeufin.nexus.*
import tech.libeufin.nexus.Database as NexusDb
import tech.libeufin.nexus.TalerAmount as NexusAmount
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.clikt.core.CliktCommand
import kotlin.test.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode

fun CliktCommand.run(cmd: String) {
    val result = test(cmd)
    if (result.statusCode != 0)
        throw Exception(result.output)
    println(result.output)
}

suspend fun HttpResponse.assertStatus(status: HttpStatusCode): HttpResponse {
    assertEquals(status, this.status);
    return this
}

suspend fun HttpResponse.assertCreated(): HttpResponse 
    = assertStatus(HttpStatusCode.Created)
suspend fun HttpResponse.assertNoContent(): HttpResponse 
    = assertStatus(HttpStatusCode.NoContent)

fun randBytes(lenght: Int): ByteArray {
    val bytes = ByteArray(lenght)
    kotlin.random.Random.nextBytes(bytes)
    return bytes
}

class IntegrationTest {
    @Test
    fun db() {
        val nexusCmd = LibeufinNexusCommand()
        nexusCmd.run("dbinit -c ../bank/conf/test.conf -r")
        val bankCmd = LibeufinBankCommand();
        bankCmd.run("dbinit -c ../bank/conf/test.conf -r")
        bankCmd.run("conversion-setup -c ../bank/conf/test.conf")
        kotlin.concurrent.thread(isDaemon = true)  {
            bankCmd.run("serve -c ../bank/conf/test.conf")
        }
        runBlocking {
            val client = HttpClient(CIO) {
                install(HttpRequestRetry) {
                    maxRetries = 10
                    constantDelay(200, 100)
                }
            }
            val nexusDb = NexusDb("postgresql:///libeufincheck")
            val userPayTo = IbanPayTo(genIbanPaytoUri())
            val fiatPayTo = IbanPayTo(genIbanPaytoUri())

            // Create user
            client.post("http://0.0.0.0:8080/accounts") {
                json {
                    "username" to "customer"
                    "password" to "password"
                    "name" to "JohnSmith"
                    "internal_payto_uri" to userPayTo
                    "cashout_payto_uri" to fiatPayTo
                    "challenge_contact_data" to obj {
                        "phone" to "+99"
                    }
                }
            }.assertCreated()

            // Cashin
            val reservePub = randBytes(32);
            nexusDb.incomingTalerablePaymentCreate(IncomingPayment(
                amount = NexusAmount(44, 0, "EUR"),
                debitPaytoUri = userPayTo.canonical,
                wireTransferSubject = "cashin test",
                executionTime = Instant.now(),
                bankTransferId = "entropic"), 
            reservePub)
            val converted = client.get("http://0.0.0.0:8080/conversion-info/cashin-rate?amount_debit=EUR:44.0")
                .assertOkJson<ConversionResponse>().amount_credit
            client.get("http://0.0.0.0:8080/accounts/customer/transactions") {
                basicAuth("customer", "password")
            }.assertOkJson<BankAccountTransactionsResponse> {
                val tx = it.transactions[0]
                assertEquals(userPayTo.canonical, tx.creditor_payto_uri)
                assertEquals("cashin test", tx.subject)
                assertEquals(converted, tx.amount)
            }

            // Cashout
            val requestUid = randBytes(32);
            val amount = BankAmount("KUDOS:25")
            val convert = client.get("http://0.0.0.0:8080/conversion-info/cashout-rate?amount_debit=$amount")
                .assertOkJson<ConversionResponse>().amount_credit;
            client.post("http://0.0.0.0:8080/accounts/customer/cashouts") {
                basicAuth("customer", "password")
                json {
                    "request_uid" to ShortHashCode(requestUid)
                    "amount_debit" to amount
                    "amount_credit" to convert
                }
            }.assertOkJson<CashoutPending> {
                val code = File("/tmp/tan-+99.txt").readText()
                client.post("http://0.0.0.0:8080/accounts/customer/cashouts/${it.cashout_id}/confirm") {
                    basicAuth("customer", "password")
                    json { "tan" to code }
                }.assertNoContent()
            }
        }
    }
}
