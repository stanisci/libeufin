/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.
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

fun CliktCommand.run(arg: String): Boolean {
    val res = this.test(arg)
    if (res.statusCode != 0) {
        println("\u001b[;31mERROR ${res.statusCode}\u001b[0m")
    } else {
        println("\u001b[;32mOK\u001b[0m")
    }
    return res.statusCode == 0
}

data class Kind(val name: String, val settings: String?) {
    val test get() = settings != null
}

class Cli : CliktCommand("Run integration tests on banks provider") {
    val platform by argument()

    override fun run() {
        // List available platform
        val platforms = Path("test").listDirectoryEntries().filter { it.isDirectory() }.map { it.fileName.toString() }
        if (!platforms.contains(platform)) {
            println("Unknown platform '$platform', expected one of $platforms")
            throw ProgramResult(1)
        }

        // Augment config
        val simpleCfg = Path("test/$platform/ebics.conf").readText()
        val conf = Path("test/$platform/ebics.edited.conf")
        conf.writeText(
        """$simpleCfg
        [paths]
        LIBEUFIN_NEXUS_HOME = test/$platform

        [nexus-fetch]
        FREQUENCY = 5s

        [nexus-submit]
        FREQUENCY = 5s

        [libeufin-nexusdb-postgres]
        CONFIG = postgres:///libeufincheck
        """)
        val cfg = loadConfig(conf)

        // Check if platform is known
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

        // Alternative payto ?
        val payto = "payto://iban/CH6208704048981247126?receiver-name=Grothoff%20Hans"
        
        runBlocking {
            step("Init ${kind.name}")

            assert(nexusCmd.run("dbinit $flags"))

            val cmds = buildMap<String, suspend () -> Unit> {
                fun put(name: String, args: String) {
                    put(name, suspend {
                        nexusCmd.run(args)
                        Unit
                    })
                }
                fun put(name: String, step: String, args: String) {
                    put(name, suspend {
                        step(step)
                        nexusCmd.run(args)
                        Unit
                    })
                }
                put("reset-db", "dbinit -r $flags")
                put("recover", "Recover old transactions", "ebics-fetch $ebicsFlags --pinned-start 2024-01-01 notification")
                put("fetch", "Fetch all documents", "ebics-fetch $ebicsFlags")
                put("ack", "Fetch CustomerAcknowledgement", "ebics-fetch $ebicsFlags acknowledgement")
                put("status", "Fetch CustomerPaymentStatusReport", "ebics-fetch $ebicsFlags status")
                put("notification", "Fetch BankToCustomerDebitCreditNotification", "ebics-fetch $ebicsFlags notification")
                put("submit", "Submit pending transactions", "ebics-submit $ebicsFlags")
                if (kind.test) {
                    put("reset-keys", suspend {
                        clientKeysPath.deleteIfExists()
                        bankKeysPath.deleteIfExists()
                        Unit
                    })
                    put("tx", suspend {
                        step("Submit one transaction")
                        nexusCmd.run("initiate-payment $flags \"$payto&amount=CHF:42&message=single%20transaction%20test\"")
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                    put("txs", suspend {
                        step("Submit many transaction")
                        repeat(4) {
                            nexusCmd.run("initiate-payment $flags --amount=CHF:${100L+it} --subject \"multi transaction test $it\" \"$payto\"")
                        }
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                } else {
                    put("tx", suspend {
                        step("Submit new transaction")
                        // TODO interactive payment editor
                        nexusCmd.run("initiate-payment $flags \"$payto&amount=CHF:1.1&message=single%20transaction%20test\"")
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                }
            }

            while (true) {
                // EBICS setup
                while (true) {
                    var clientKeys = loadClientKeys(clientKeysPath)
                    var bankKeys = loadBankKeys(bankKeysPath)
                    if (!kind.test && clientKeys == null) {
                        throw Exception("Clients keys are required to run netzbon tests")
                    } else if (clientKeys == null || !clientKeys.submitted_ini) {
                        step("Run INI and HIA order")
                    } else if (!clientKeys.submitted_hia) {
                        step("Run HIA order")
                    } else if (bankKeys == null || !bankKeys.accepted) {
                        step("Run HPB order")
                        if (kind.test)
                            ask("Got to ${kind.settings} and click on 'Activate EBICS user'.\nPress Enter when done>")
                    } else {
                        break
                    }
                    if (!nexusCmd.run("ebics-setup --auto-accept-keys $flags")) {
                        clientKeys = loadClientKeys(clientKeysPath)
                        if (kind.test) {
                            if (clientKeys == null || !clientKeys.submitted_ini || !clientKeys.submitted_hia) {
                                ask("Got to ${kind.settings} and click on 'Reset EBICS user'.\nPress Enter when done>")
                            } else {
                                ask("Got to ${kind.settings} and click on 'Activate EBICS user'.\nPress Enter when done>")
                            }
                        } else {
                            ask("Activate your keys at your bank.\nPress Enter when done>")
                        }
                    }
                }

                val arg = ask("testbench> ")!!.trim()
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
