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
import tech.libeufin.bank.TalerAmount as BankAmount
import tech.libeufin.nexus.*
import tech.libeufin.nexus.Database as NexusDb
import tech.libeufin.nexus.TalerAmount as NexusAmount
import tech.libeufin.bank.AccountDAO.*
import tech.libeufin.util.*
import java.io.File
import java.time.Instant
import java.util.Arrays
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

fun HttpResponse.assertNoContent() {
    assertEquals(HttpStatusCode.NoContent, this.status)
}

fun randBytes(lenght: Int): ByteArray {
    val bytes = ByteArray(lenght)
    kotlin.random.Random.nextBytes(bytes)
    return bytes
}

class IntegrationTest {
    val nexusCmd = LibeufinNexusCommand()
    val bankCmd = LibeufinBankCommand();
    val client = HttpClient(CIO) {
        install(HttpRequestRetry) {
            maxRetries = 10
            constantDelay(200, 100)
        }
    }

    @Test
    fun mini() {
        bankCmd.run("dbinit -c conf/mini.conf -r")
        bankCmd.run("passwd admin password -c conf/mini.conf")
        kotlin.concurrent.thread(isDaemon = true)  {
            bankCmd.run("serve -c conf/mini.conf")
        }
        
        runBlocking {
            // Check bank is running
            client.get("http://0.0.0.0:8090/public-accounts").assertNoContent()
        }
    }

    @Test
    fun conversion() {
        nexusCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("passwd admin password -c conf/integration.conf")
        bankCmd.run("create-account -c conf/integration.conf -u exchange -p password --name 'Mr Money' --exchange")
        kotlin.concurrent.thread(isDaemon = true)  {
            bankCmd.run("serve -c conf/integration.conf")
        }
        
        runBlocking {
            val nexusDb = NexusDb("postgresql:///libeufincheck")
            val userPayTo = IbanPayTo(genIbanPaytoUri())
            val fiatPayTo = IbanPayTo(genIbanPaytoUri())

            // Create user
            client.post("http://0.0.0.0:8080/accounts") {
                basicAuth("admin", "password")
                json {
                    "username" to "customer"
                    "password" to "password"
                    "name" to "JohnSmith"
                    "internal_payto_uri" to userPayTo
                    "cashout_payto_uri" to fiatPayTo
                    "debit_threshold" to "KUDOS:100"
                    "challenge_contact_data" to obj {
                        "phone" to "+99"
                    }
                }
            }.assertOkJson<RegisterAccountResponse>()

            // Set conversion rates
            client.post("http://0.0.0.0:8080/conversion-info/conversion-rate") {
                basicAuth("admin", "password")
                json {
                    "cashin_ratio" to "0.8"
                    "cashin_fee" to "KUDOS:0.02"
                    "cashin_tiny_amount" to "KUDOS:0.01"
                    "cashin_rounding_mode" to "nearest"
                    "cashin_min_amount" to "EUR:0"
                    "cashout_ratio" to "1.25"
                    "cashout_fee" to "EUR:0.003"
                    "cashout_tiny_amount" to "EUR:0.00000001"
                    "cashout_rounding_mode" to "zero"
                    "cashout_min_amount" to "KUDOS:0.1"
                }
            }.assertNoContent()

            // Set admin debit threshold
            client.patch("http://0.0.0.0:8080/accounts/admin") {
                basicAuth("admin", "password")
                json {
                    "debit_threshold" to "KUDOS:1000"
                }
            }.assertNoContent()

            // Cashin
            repeat(3) { i ->
                val reservePub = randBytes(32);
                val amount = NexusAmount(20L + i, 0, "EUR")
                nexusDb.incomingTalerablePaymentCreate(IncomingPayment(
                    amount = amount,
                    debitPaytoUri = userPayTo.canonical,
                    wireTransferSubject = "cashin test $i",
                    executionTime = Instant.now(),
                    bankTransferId = "entropic"), 
                reservePub)
                val converted = client.get("http://0.0.0.0:8080/conversion-info/cashin-rate?amount_debit=EUR:${20 + i}")
                    .assertOkJson<ConversionResponse>().amount_credit
                client.get("http://0.0.0.0:8080/accounts/exchange/transactions") {
                    basicAuth("exchange", "password")
                }.assertOkJson<BankAccountTransactionsResponse> {
                    val tx = it.transactions.first()
                    assertEquals("cashin test $i", tx.subject)
                    assertEquals(converted, tx.amount)
                }
                client.get("http://0.0.0.0:8080/accounts/exchange/taler-wire-gateway/history/incoming") {
                    basicAuth("exchange", "password")
                }.assertOkJson<IncomingHistory> {
                    val tx = it.incoming_transactions.first()
                    assertEquals(converted, tx.amount)
                    assert(Arrays.equals(reservePub, tx.reserve_pub.raw))
                }
            }

            // Cashout
            repeat(3) { i ->  
                val requestUid = randBytes(32);
                val amount = BankAmount("KUDOS:${10+i}")
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
}
