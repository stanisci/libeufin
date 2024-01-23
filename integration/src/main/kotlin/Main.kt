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

package tech.libeufin.integration

import tech.libeufin.nexus.Database as NexusDb
import tech.libeufin.nexus.TalerAmount as NexusAmount
import tech.libeufin.nexus.*
import tech.libeufin.bank.*
import tech.libeufin.util.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.clikt.testing.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlin.test.*
import java.io.File
import java.nio.file.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.*
import net.taler.wallet.crypto.Base32Crockford
import kotlin.io.path.*

fun randBytes(lenght: Int): ByteArray {
    val bytes = ByteArray(lenght)
    kotlin.random.Random.nextBytes(bytes)
    return bytes
}

val nexusCmd = LibeufinNexusCommand()
val client = HttpClient(CIO)

fun step(name: String) {
    println("\u001b[35m$name\u001b[0m")
}

fun ask(question: String): String? {
    print("\u001b[;1m$question\u001b[0m")
    System.out.flush()
    return readlnOrNull()
}

fun CliktCommandTestResult.assertOk(msg: String? = null) {
    println("$output")
    assertEquals(0, statusCode, msg)
}

fun CliktCommandTestResult.assertErr(msg: String? = null) {
    println("$output")
    assertEquals(1, statusCode, msg)
}

enum class Kind {
    postfinance, 
    netzbon
}

class Cli : CliktCommand("Run integration tests on banks provider") {
    val kind: Kind by argument().enum<Kind>()
    override fun run() {
        val name = kind.name
        step("Test init $name")

        runBlocking {
            Path("test/$name").createDirectories()
            val conf = "conf/$name.conf"
            val log = "DEBUG"
            val flags = " -c $conf -L $log"
            val ebicsFlags = "$flags --transient --debug-ebics test/$name"
            val cfg = loadConfig(conf)

            val clientKeysPath = Path(cfg.requireString("nexus-ebics", "client_private_keys_file"))
            val bankKeysPath = Path(cfg.requireString("nexus-ebics", "bank_public_keys_file"))
        
            var hasClientKeys = clientKeysPath.exists()
            var hasBankKeys = bankKeysPath.exists()

            if (ask("Reset DB ? y/n>") == "y") nexusCmd.test("dbinit -r $flags").assertOk()
            else nexusCmd.test("dbinit $flags").assertOk()
            val nexusDb = NexusDb("postgresql:///libeufincheck")

            when (kind) {
                Kind.postfinance -> {
                    if (hasClientKeys || hasBankKeys) {
                        if (ask("Reset keys ? y/n>") == "y") {
                            if (hasClientKeys) clientKeysPath.deleteIfExists()
                            if (hasBankKeys) bankKeysPath.deleteIfExists()
                            hasClientKeys = false
                            hasBankKeys = false
                        }
                    }
                  
                    if (!hasClientKeys) {
                        step("Test INI order")
                        ask("Got to https://isotest.postfinance.ch/corporates/user/settings/ebics and click on 'Reset EBICS user'.\nPress Enter when done>")
                        nexusCmd.test("ebics-setup $flags")
                            .assertErr("ebics-setup should failed the first time")
                    }
        
                    if (!hasBankKeys) {
                        step("Test HIA order")
                        ask("Got to https://isotest.postfinance.ch/corporates/user/settings/ebics and click on 'Activate EBICS user'.\nPress Enter when done>")
                        nexusCmd.test("ebics-setup --auto-accept-keys $flags")
                            .assertOk("ebics-setup should succeed the second time")
                    }
                   
                    val payto = "payto://iban/CH2989144971918294289?receiver-name=Test"
        
                    step("Test fetch transactions")
                    nexusCmd.test("ebics-fetch $ebicsFlags --pinned-start 2022-01-01").assertOk()

                    while (true) {
                        when (ask("Run 'fetch', 'submit', 'tx', 'txs', 'logs', 'ack' or 'exit'>")) {
                            "fetch" -> {
                                step("Fetch new transactions")
                                nexusCmd.test("ebics-fetch $ebicsFlags").assertOk()
                            }
                            "tx" -> {
                                step("Test submit one transaction")
                                nexusDb.initiatedPaymentCreate(InitiatedPayment(
                                    amount = NexusAmount(42L, 0, "CFH"),
                                    creditPaytoUri = payto,
                                    wireTransferSubject = "single transaction test",
                                    initiationTime = Instant.now(),
                                    requestUid = Base32Crockford.encode(randBytes(16))
                                ))
                                nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                            }
                            "txs" -> {
                                step("Test submit many transaction")
                                repeat(4) {
                                    nexusDb.initiatedPaymentCreate(InitiatedPayment(
                                        amount = NexusAmount(100L + it, 0, "CFH"),
                                        creditPaytoUri = payto,
                                        wireTransferSubject = "multi transaction test $it",
                                        initiationTime = Instant.now(),
                                        requestUid = Base32Crockford.encode(randBytes(16))
                                    ))
                                }
                                nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                            }
                            "submit" -> {
                                step("Submit pending transactions")
                                nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                            }
                            "logs" -> {
                                step("Fetch logs")
                                nexusCmd.test("ebics-fetch $ebicsFlags --only-logs").assertOk()
                            }
                            "ack" -> {
                                step("Fetch ack")
                                nexusCmd.test("ebics-fetch $ebicsFlags --only-ack").assertOk()
                            }
                            "exit" -> break
                        }
                    }
                }
                Kind.netzbon -> {
                    if (!hasClientKeys)
                        throw Exception("Clients keys are required to run netzbon tests")
                        
                    if (!hasBankKeys) {
                        step("Test HIA order")
                        nexusCmd.test("ebics-setup --auto-accept-keys $flags").assertOk("ebics-setup should succeed the second time")
                    }
    
                    step("Test fetch transactions")
                    nexusCmd.test("ebics-fetch $ebicsFlags --pinned-start 2022-01-01").assertOk()

                    while (true) {
                        when (ask("Run 'fetch', 'submit', 'logs', 'ack' or 'exit'>")) {
                            "fetch" -> {
                                step("Fetch new transactions")
                                nexusCmd.test("ebics-fetch $ebicsFlags").assertOk()
                            }
                            "submit" -> {
                                step("Submit pending transactions")
                                nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                            }
                            "tx" -> {
                                step("Submit new transaction")
                                // TODO interactive payment editor
                                nexusDb.initiatedPaymentCreate(InitiatedPayment(
                                    amount = getTalerAmount("1.1", "CFH"),
                                    creditPaytoUri = "payto://iban/CH6208704048981247126?receiver-name=Grothoff%20Hans",
                                    wireTransferSubject = "single transaction test",
                                    initiationTime = Instant.now(),
                                    requestUid = Base32Crockford.encode(randBytes(16))
                                ))
                                nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                            }
                            "logs" -> {
                                step("Fetch logs")
                                nexusCmd.test("ebics-fetch $ebicsFlags --only-logs").assertOk()
                            }
                            "ack" -> {
                                step("Fetch ack")
                                nexusCmd.test("ebics-fetch $ebicsFlags --only-ack").assertOk()
                            }
                            "exit" -> break
                        }
                    }
                }
            }
        }
                
        step("Test succeed")
    }
}

fun main(args: Array<String>) {
    Cli().main(args)
}
