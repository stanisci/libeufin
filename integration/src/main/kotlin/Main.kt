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

fun CliktCommand.run(cmd: String) {
    val result = test(cmd)
    if (result.statusCode != 0)
        throw Exception(result.output)
    println(result.output)
}

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

fun CliktCommandTestResult.assertOk(msg: String? = null) {
    assertEquals(0, statusCode, "msg\n$output")
}

fun CliktCommandTestResult.assertErr(msg: String? = null) {
    assertEquals(1, statusCode, "msg\n$output")
}

class PostFinanceCli : CliktCommand("Run tests on postfinance", name="postfinance") {
    override fun run() {
        runBlocking {
            Files.createDirectories(Paths.get("test/postfinance"))
            val conf = "conf/postfinance.conf"
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
                step("Test INI order")
                println("Got to https://testplattform.postfinance.ch/corporates/user/settings/ebics and click on 'Reset EBICS user'.\nPress Enter when done>")
                readlnOrNull()
                nexusCmd.test("ebics-setup -c $conf")
                    .assertErr("ebics-setup should failed the first time")
            }

            if (!hasBankKeys) {
                step("Test HIA order")
                println("Got to https://testplattform.postfinance.ch/corporates/user/settings/ebics and click on 'Activate EBICS user'.\nPress Enter when done>")
                readlnOrNull()
                nexusCmd.test("ebics-setup --auto-accept-keys -c $conf")
                    .assertOk("ebics-setup should succeed the second time")
            }
            val payto = "payto://iban/CH2989144971918294289?receiver-name=Test"

            step("Test submit one transaction")
            val nexusDb = NexusDb("postgresql:///libeufincheck")
            nexusCmd.test("dbinit -r -c $conf").assertOk()
            nexusDb.initiatedPaymentCreate(InitiatedPayment(
                amount = NexusAmount(42L, 0, "CFH"),
                creditPaytoUri = payto,
                wireTransferSubject = "single transaction test",
                initiationTime = Instant.now(),
                requestUid = Base32Crockford.encode(randBytes(16))
            ))
            nexusCmd.test("ebics-submit --transient -c $conf").assertOk()

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
            nexusCmd.test("ebics-submit --transient -c $conf").assertOk()

            step("Test succeed")
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
