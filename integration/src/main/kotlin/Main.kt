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

import tech.libeufin.nexus.*
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlin.test.*
import java.io.File
import java.nio.file.*
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.*

fun CliktCommand.run(cmd: String) {
    val result = test(cmd)
    if (result.statusCode != 0)
        throw Exception(result.output)
    println(result.output)
}

val nexusCmd = LibeufinNexusCommand()
val client = HttpClient(CIO)

class PostFinanceCli : CliktCommand("Run tests on postfinance", name="postfinance") {
    override fun run() {
        runBlocking {
            Files.createDirectories(Paths.get("test/postfinance"))
            val clientKeysPath = Paths.get("test/postfinance/client-keys.json")
            val bankKeysPath = Paths.get("test/postfinance/bank-keys.json")

            var hasClientKeys = Files.exists(clientKeysPath)
            var hasBankKeys = Files.exists(bankKeysPath)

            if (hasClientKeys || hasBankKeys) {
                println("Reset keys ? y/n>")
                if (readlnOrNull() == "y") {
                    if (hasClientKeys) Files.deleteIfExists(clientKeysPath)
                    if (hasBankKeys) Files.deleteIfExists(bankKeysPath)
                    hasClientKeys = false
                    hasBankKeys = false
                }
            }
          
            if (!hasClientKeys) {
                // Test INI order
                println("Got to https://testplattform.postfinance.ch/corporates/user/settings/ebics and click on 'Reset EBICS user'.\nPress Enter when done>")
                readlnOrNull()
                nexusCmd.test("ebics-setup -c conf/postfinance.conf").run {
                    assertEquals(1, statusCode, "ebics-setup should failed the first time")
                }
            }

            if (!hasBankKeys) {
                // Test HIA order
                println("Got to https://testplattform.postfinance.ch/corporates/user/settings/ebics and click on 'Activate EBICS user'.\nPress Enter when done>")
                readlnOrNull()
                val out = nexusCmd.test("ebics-setup -c conf/postfinance.conf --auto-accept-keys").run {
                    assertEquals(0, statusCode, "ebics-setup should succeed the second time")
                }
            }
        }
    }
}

class Cli : CliktCommand() {
    init {
        subcommands(PostFinanceCli())
    }
    override fun run() = Unit
}

fun main(args: Array<String>) {
    Cli().main(args)
}
