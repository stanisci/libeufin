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
import net.taler.wallet.crypto.Base32Crockford
import tech.libeufin.bank.*
import tech.libeufin.bank.TalerAmount as BankAmount
import tech.libeufin.nexus.*
import tech.libeufin.nexus.Database as NexusDb
import tech.libeufin.nexus.TalerAmount as NexusAmount
import tech.libeufin.bank.db.AccountDAO.*
import tech.libeufin.util.*
import java.io.File
import java.time.Instant
import java.util.Arrays
import java.sql.SQLException
import kotlinx.coroutines.runBlocking
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.clikt.core.CliktCommand
import org.postgresql.jdbc.PgConnection
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

fun server(lambda: () -> Unit) {
    // Start the HTTP server in another thread
    kotlin.concurrent.thread(isDaemon = true)  {
        lambda()
    }
    // Wait for the HTTP server to be up
    runBlocking {
        HttpClient(CIO) {
            install(HttpRequestRetry) {
                maxRetries = 10
                constantDelay(200, 100)
            }
        }.get("http://0.0.0.0:8080/config")
    }
   
}

fun setup(lambda: suspend (NexusDb) -> Unit) {
    try {
        runBlocking {
            NexusDb("postgresql:///libeufincheck").use {
                lambda(it)
            }
        }
    } finally {
        engine?.stop(0, 0) // Stop http server if started
    }
}

inline fun assertException(msg: String, lambda: () -> Unit) {
    try {
        lambda()
        throw Exception("Expected failure: $msg")
    } catch (e: Exception) {
        assert(e.message!!.startsWith(msg)) { "${e.message}" }
    }
}

class IntegrationTest {
    val nexusCmd = LibeufinNexusCommand()
    val bankCmd = LibeufinBankCommand();
    val client = HttpClient(CIO)

    @Test
    fun mini() {
        bankCmd.run("dbinit -c conf/mini.conf -r")
        bankCmd.run("passwd admin password -c conf/mini.conf")
        bankCmd.run("dbinit -c conf/mini.conf") // Indempotent
        
        server {
            bankCmd.run("serve -c conf/mini.conf")
        }
        
        setup { _ ->
            // Check bank is running
            client.get("http://0.0.0.0:8080/public-accounts").assertNoContent()
        }
    }

    @Test
    fun errors() {
        nexusCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("passwd admin password -c conf/integration.conf")

        suspend fun checkCount(db: NexusDb, nbIncoming: Int, nbBounce: Int, nbTalerable: Int) {
            db.runConn { conn ->
                conn.prepareStatement("SELECT count(*) FROM incoming_transactions").oneOrNull {
                    assertEquals(nbIncoming, it.getInt(1))
                }
                conn.prepareStatement("SELECT count(*) FROM bounced_transactions").oneOrNull {
                    assertEquals(nbBounce, it.getInt(1))
                }
                conn.prepareStatement("SELECT count(*) FROM talerable_incoming_transactions").oneOrNull {
                    assertEquals(nbTalerable, it.getInt(1))
                }
            }
        }

        setup { db ->
            val userPayTo = IbanPayTo(genIbanPaytoUri())
            val fiatPayTo = IbanPayTo(genIbanPaytoUri())
    
            // Load conversion setup manually as the server would refuse to start without an exchange account
            val sqlProcedures = File("../database-versioning/libeufin-conversion-setup.sql")
            db.runConn { 
                it.execSQLUpdate(sqlProcedures.readText())
                it.execSQLUpdate("SET search_path TO libeufin_nexus;")
            }

            val reservePub = randBytes(32)
            val payment = IncomingPayment(
                amount = NexusAmount(10, 0, "EUR"),
                debitPaytoUri = userPayTo.canonical,
                wireTransferSubject = "Error test ${Base32Crockford.encode(reservePub)}",
                executionTime = Instant.now(),
                bankTransferId = "error"
            )

            assertException("ERROR: cashin failed: missing exchange account") {
                ingestIncomingPayment(db, payment)
            }

            // Create exchange account
            bankCmd.run("create-account -c conf/integration.conf -u exchange -p password --name 'Mr Money' --exchange")
    
            assertException("ERROR: cashin currency conversion failed: missing conversion rates") {
                ingestIncomingPayment(db, payment)
            }

            // Start server
            server {
                bankCmd.run("serve -c conf/integration.conf")
            }

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
            
            assertException("ERROR: cashin failed: admin balance insufficient") {
                db.registerTalerableIncoming(payment, reservePub)
            }

            // Allow admin debt
            bankCmd.run("edit-account admin --debit_threshold KUDOS:100 -c conf/integration.conf")

            // Too small amount
            checkCount(db, 0, 0, 0)
            ingestIncomingPayment(db, payment.copy(
                amount = NexusAmount(0, 10, "EUR"),
            ))
            checkCount(db, 1, 1, 0)
            client.get("http://0.0.0.0:8080/accounts/exchange/transactions") {
                basicAuth("exchange", "password")
            }.assertNoContent()

            // Check success
            ingestIncomingPayment(db, IncomingPayment(
                amount = NexusAmount(10, 0, "EUR"),
                debitPaytoUri = userPayTo.canonical,
                wireTransferSubject = "Success ${Base32Crockford.encode(randBytes(32))}",
                executionTime = Instant.now(),
                bankTransferId = "success"
            ))
            checkCount(db, 2, 1, 1)
            client.get("http://0.0.0.0:8080/accounts/exchange/transactions") {
                basicAuth("exchange", "password")
            }.assertOkJson<BankAccountTransactionsResponse>()

            // TODO check double insert cashin with different subject
        }
    }

    @Test
    fun conversion() {
        nexusCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("dbinit -c conf/integration.conf -r")
        bankCmd.run("passwd admin password -c conf/integration.conf")
        bankCmd.run("edit-account admin --debit_threshold KUDOS:1000 -c conf/integration.conf")
        bankCmd.run("create-account -c conf/integration.conf -u exchange -p password --name 'Mr Money' --exchange")
        nexusCmd.run("dbinit -c conf/integration.conf") // Idempotent
        bankCmd.run("dbinit -c conf/integration.conf") // Idempotent

        server {
            bankCmd.run("serve -c conf/integration.conf")
        }
        
        setup { db -> 
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
                    "contact_data" to obj {
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

            // Cashin
            repeat(3) { i ->
                val reservePub = randBytes(32);
                val amount = NexusAmount(20L + i, 0, "EUR")
                val subject = "cashin test $i: ${Base32Crockford.encode(reservePub)}"
                ingestIncomingPayment(db, 
                    IncomingPayment(
                        amount = amount,
                        debitPaytoUri = userPayTo.canonical,
                        wireTransferSubject = subject,
                        executionTime = Instant.now(),
                        bankTransferId = Base32Crockford.encode(reservePub)
                    )
                )
                val converted = client.get("http://0.0.0.0:8080/conversion-info/cashin-rate?amount_debit=EUR:${20 + i}")
                    .assertOkJson<ConversionResponse>().amount_credit
                client.get("http://0.0.0.0:8080/accounts/exchange/transactions") {
                    basicAuth("exchange", "password")
                }.assertOkJson<BankAccountTransactionsResponse> {
                    val tx = it.transactions.first()
                    assertEquals(subject, tx.subject)
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
                }.assertOkJson<CashoutResponse>()
            }
        }
    }
}
