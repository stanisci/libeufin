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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.testing.test
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import tech.libeufin.nexus.LibeufinNexusCommand
import tech.libeufin.nexus.loadBankKeys
import tech.libeufin.nexus.loadClientKeys
import tech.libeufin.nexus.loadConfig
import tech.libeufin.nexus.loadJsonFile
import kotlin.io.path.*

val nexusCmd = LibeufinNexusCommand()
val client = HttpClient(CIO)

fun step(name: String) {
    println("\u001b[35m$name\u001b[0m")
}

fun msg(msg: String) {
    println("\u001b[33m$msg\u001b[0m")
}

fun ask(question: String): String? {
    print("\u001b[;1m$question\u001b[0m")
    System.out.flush()
    return readlnOrNull()
}

fun CliktCommand.run(arg: String): Boolean {
    val res = this.test(arg)
    print(res.output)
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

@Serializable
data class Config(
    val payto: Map<String, String>
)

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

        // Read testbench config 
        val benchCfg: Config = loadJsonFile(Path("test/config.json"), "testbench config")
            ?: Config(emptyMap())

        // Prepare cmds
        val log = "DEBUG"
        val flags = " -c $conf -L $log"
        val ebicsFlags = "$flags --transient --debug-ebics test/$platform"
        val clientKeysPath = cfg.requirePath("nexus-ebics", "client_private_keys_file")
        val bankKeysPath = cfg.requirePath("nexus-ebics", "bank_public_keys_file")
        val currency = cfg.requireString("nexus-ebics", "currency")

        val dummyPaytos = mapOf(
            "CHF" to "payto://iban/CH4189144589712575493?receiver-name=John%20Smith",
            "EUR" to "payto://iban/DE54500105177452372744?receiver-name=John%20Smith"
        )
        val dummyPayto = dummyPaytos[currency] 
            ?: throw Exception("Missing dummy payto for $currency")
        val payto = benchCfg.payto[currency] ?: dummyPayto
                        ?: throw Exception("Missing test payto for $currency")
        
        val recoverDoc = when (cfg.requireString("nexus-ebics", "bank_dialect")) {
            "gls" -> "statement"
            else -> "notification"
        }
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
                put("recover", "Recover old transactions", "ebics-fetch $ebicsFlags --pinned-start 2024-01-01 $recoverDoc")
                put("fetch", "Fetch all documents", "ebics-fetch $ebicsFlags")
                put("ack", "Fetch CustomerAcknowledgement", "ebics-fetch $ebicsFlags acknowledgement")
                put("status", "Fetch CustomerPaymentStatusReport", "ebics-fetch $ebicsFlags status")
                put("notification", "Fetch BankToCustomerDebitCreditNotification", "ebics-fetch $ebicsFlags notification")
                put("statement", "Fetch BankToCustomerStatement", "ebics-fetch $ebicsFlags statement")
                put("submit", "Submit pending transactions", "ebics-submit $ebicsFlags")
                put("setup", "Setup", "ebics-setup $flags")
                put("reset-keys", suspend {
                    if (kind.test) {
                        clientKeysPath.deleteIfExists()
                    }
                    bankKeysPath.deleteIfExists()
                    Unit
                })
                if (kind.test) {
                    put("tx", suspend {
                        step("Submit one transaction")
                        nexusCmd.run("initiate-payment $flags \"$payto&amount=$currency:42&message=single%20transaction%20test\"")
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                    put("txs", suspend {
                        step("Submit many transaction")
                        repeat(4) {
                            nexusCmd.run("initiate-payment $flags --amount=$currency:${100L+it} --subject \"multi transaction test $it\" \"$payto\"")
                        }
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                } else {
                    put("tx", suspend {
                        step("Submit new transaction")
                        nexusCmd.run("initiate-payment $flags \"$payto&amount=$currency:1.1&message=single%20transaction%20test\"")
                        nexusCmd.run("ebics-submit $ebicsFlags")
                        Unit
                    })
                }
                put("tx-bad-name", suspend {
                    val badPayto = URLBuilder().takeFrom(payto)
                    badPayto.parameters.set("receiver-name", "John Smith")
                    step("Submit new transaction with a bad name")
                    nexusCmd.run("initiate-payment $flags \"$badPayto&amount=$currency:1.1&message=This%20should%20fail%20because%20bad%20name\"")
                    nexusCmd.run("ebics-submit $ebicsFlags")
                    Unit
                })
                put("tx-dummy", suspend {
                    step("Submit new transaction to a dummy IBAN")
                    nexusCmd.run("initiate-payment $flags \"$dummyPayto&amount=$currency:1.1&message=This%20should%20fail%20because%20dummy\"")
                    nexusCmd.run("ebics-submit $ebicsFlags")
                    Unit
                })
            }
            while (true) {
                var clientKeys = loadClientKeys(clientKeysPath)
                var bankKeys = loadBankKeys(bankKeysPath)
                if (!kind.test && clientKeys == null) {
                    throw Exception("Clients keys are required to run netzbon tests")
                } else if (clientKeys == null || !clientKeys.submitted_ini || !clientKeys.submitted_hia || bankKeys == null || !bankKeys.accepted) {
                    step("Run EBICS setup")
                    if (!nexusCmd.run("ebics-setup --auto-accept-keys $flags")) {
                        clientKeys = loadClientKeys(clientKeysPath)
                        if (kind.test) {
                            if (clientKeys == null || !clientKeys.submitted_ini || !clientKeys.submitted_hia) {
                                msg("Got to ${kind.settings} and click on 'Reset EBICS user'")
                            } else {
                                msg("Got to ${kind.settings} and click on 'Activate EBICS user'")
                            }
                        } else {
                            msg("Activate your keys at your bank")
                        }
                    }
                }
                val arg = ask("testbench> ")!!.trim()
                if (arg == "exit") break
                if (arg == "") continue
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
