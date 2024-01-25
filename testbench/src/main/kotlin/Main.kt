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

package tech.libeufin.testbench

import tech.libeufin.nexus.Database as NexusDb
import tech.libeufin.nexus.*
import tech.libeufin.bank.*
import tech.libeufin.common.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.clikt.testing.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlin.test.*
import kotlin.io.path.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.*

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

data class Kind(val name: String, val settings: String?) {
    val test get() = settings != null
}

class Cli : CliktCommand("Run integration tests on banks provider") {
    val platform by argument()

    override fun run() {
        // List available platform
        val platforms = Path("test").listDirectoryEntries().filter { it.isDirectory() }.map { it.getFileName().toString() }
        if (!platforms.contains(platform)) {
            println("Unknown platform '$platform', expected one of $platforms")
            throw ProgramResult(1)
        }

        // Augment config
        val simpleCfg = Path("test/$platform/ebics.conf").readText()
        val conf = "test/$platform/ebics.edited.conf"
        Path(conf).writeText("""$simpleCfg
        [paths]
        LIBEUFIN_NEXUS_HOME = test/$platform

        [nexus-fetch]
        FREQUENCY = 5s

        [nexus-submit]
        FREQUENCY = 5s

        [nexus-postgres]
        CONFIG = postgres:///libeufincheck
        """)
        val cfg = loadConfig(conf)

        // Check if paltform is known
        val kind = when (cfg.requireString("nexus-ebics", "host_base_url")) {
            "https://isotest.postfinance.ch/ebicsweb/ebicsweb" -> 
                Kind("PostFinance IsoTest", "https://isotest.postfinance.ch/corporates/user/settings/ebics")
            "https://iso20022test.credit-suisse.com/ebicsweb/ebicsweb" ->
                Kind("Credit Suisse isoTest", "https://iso20022test.credit-suisse.com/user/settings/ebics")   
            "https://ebics.postfinance.ch/ebics/ebics.aspx" -> 
                Kind("PostFinance", null)
            else -> Kind("Unknown", null)
        }

        // Prepare cmds
        val log = "DEBUG"
        val flags = " -c $conf -L $log"
        val ebicsFlags = "$flags --transient --debug-ebics test/$platform"
        val clientKeysPath = cfg.requirePath("nexus-ebics", "client_private_keys_file")
        val bankKeysPath = cfg.requirePath("nexus-ebics", "bank_public_keys_file")

        var hasClientKeys = clientKeysPath.exists()
        var hasBankKeys = bankKeysPath.exists()

        // Alternative payto ?
        val payto = "payto://iban/CH6208704048981247126?receiver-name=Grothoff%20Hans"
        
        runBlocking {
            step("Test init ${kind.name}")

            if (ask("Reset DB ? y/n>") == "y") nexusCmd.test("dbinit -r $flags").assertOk()
            else nexusCmd.test("dbinit $flags").assertOk()
            val nexusDb = NexusDb("postgresql:///libeufincheck")

            val cmds = buildMap<String, suspend () -> Unit> {
                put("reset-db", suspend {
                    nexusCmd.test("dbinit -r $flags").assertOk()
                })
                put("recover", suspend {
                    step("Recover old transactions")
                    nexusCmd.test("ebics-fetch $ebicsFlags --pinned-start 2022-01-01").assertOk()
                })
                put("fetch", suspend {
                    step("Fetch new transactions")
                    nexusCmd.test("ebics-fetch $ebicsFlags").assertOk()
                })
                put("submit", suspend {
                    step("Submit pending transactions")
                    nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                })
                put("logs", suspend {
                    step("Fetch HAC logs")
                    nexusCmd.test("ebics-fetch $ebicsFlags --only-logs").assertOk()
                })
                put("ack", suspend {
                    step("Fetch CustomerPaymentStatusReport")
                    nexusCmd.test("ebics-fetch $ebicsFlags --only-ack").assertOk()
                })
                if (kind.test) {
                    put("reset-keys", suspend {
                        clientKeysPath.deleteIfExists()
                        bankKeysPath.deleteIfExists()
                        hasClientKeys = false
                        hasBankKeys = false
                    })
                    put("tx", suspend {
                        step("Test submit one transaction")
                        nexusDb.initiatedPaymentCreate(InitiatedPayment(
                            amount = TalerAmount("CFH:42"),
                            creditPaytoUri = IbanPayto(payto).requireFull(),
                            wireTransferSubject = "single transaction test",
                            initiationTime = Instant.now(),
                            requestUid = Base32Crockford.encode(randBytes(16))
                        ))
                        nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                    })
                    put("txs", suspend {
                        step("Test submit many transaction")
                        repeat(4) {
                            nexusDb.initiatedPaymentCreate(InitiatedPayment(
                                amount = TalerAmount("CFH:${100L+it}"),
                                creditPaytoUri = IbanPayto(payto).requireFull(),
                                wireTransferSubject = "multi transaction test $it",
                                initiationTime = Instant.now(),
                                requestUid = Base32Crockford.encode(randBytes(16))
                            ))
                        }
                        nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                    })
                } else {
                    put("tx", suspend {
                        step("Submit new transaction")
                        // TODO interactive payment editor
                        nexusDb.initiatedPaymentCreate(InitiatedPayment(
                            amount = TalerAmount("CFH:1.1"),
                            creditPaytoUri = IbanPayto(payto).requireFull(),
                            wireTransferSubject = "single transaction test",
                            initiationTime = Instant.now(),
                            requestUid = Base32Crockford.encode(randBytes(16))
                        ))
                        nexusCmd.test("ebics-submit $ebicsFlags").assertOk()
                    })
                }
            }

            while (true) {
                if (!hasClientKeys) {
                    if (kind.test) {
                        step("Test INI order")
                        ask("Got to ${kind.settings} and click on 'Reset EBICS user'.\nPress Enter when done>")
                        nexusCmd.test("ebics-setup $flags")
                            .assertErr("ebics-setup should failed the first time")
                        ask("Got to ${kind.settings} and click on 'Activate EBICS user'.\nPress Enter when done>")
                    } else {
                        throw Exception("Clients keys are required to run netzbon tests")
                    }
                } 
    
                if (!hasBankKeys) {
                    step("Test HIA order")
                    nexusCmd.test("ebics-setup --auto-accept-keys $flags")
                        .assertOk("ebics-setup should succeed the second time")
                }

                val arg = ask("testbench >")!!.trim()
                if (arg == "exit") break
                val cmd = cmds[arg]
                if (cmd != null) {
                    cmd()
                } else {
                    if (arg != "?" && arg != "help") {
                        println("Unknown command '$arg'")
                    }
                    println("Commands:")
                    for ((name, _) in cmds) {
                        println("  $name")
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    Cli().main(args)
}
