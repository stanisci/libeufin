/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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

import tech.libeufin.nexus.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.testing.*
import kotlin.test.*
import java.io.*
import java.nio.file.*
import kotlin.io.path.*
import tech.libeufin.util.*

val nexusCmd = LibeufinNexusCommand()

fun CliktCommand.testErr(cmd: String, msg: String) {
    val prevOut = System.err
    val tmpOut = ByteArrayOutputStream()
    System.setErr(PrintStream(tmpOut))
    val result = test(cmd)
    System.setErr(prevOut)
    val tmpStr = tmpOut.toString(Charsets.UTF_8)
    println(tmpStr)
    assertEquals(1, result.statusCode, "'$cmd' should have failed")
    val line = tmpStr.substringAfterLast(" - ").trimEnd('\n')
    println(line)
    assertEquals(msg, line)
}

class CliTest {
    /** Test error format related to the keying process */
    @Test
    fun keys() {
        val cmds = listOf("ebics-submit", "ebics-fetch")
        val allCmds = listOf("ebics-submit", "ebics-fetch", "ebics-setup")
        val conf = "conf/test.conf"
        val cfg = loadConfig(conf)
        val clientKeysPath = Path(cfg.requireString("nexus-ebics", "client_private_keys_file"))
        val bankKeysPath = Path(cfg.requireString("nexus-ebics", "bank_public_keys_file"))
        clientKeysPath.parent?.createDirectories()
        bankKeysPath.parent?.createDirectories()
        
        // Missing client keys
        clientKeysPath.deleteIfExists()
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Cannot operate without client keys. Missing '$clientKeysPath' file. Run 'libeufin-nexus ebics-setup' first")
        }
        // Bad client json
        clientKeysPath.writeText("CORRUPTION", Charsets.UTF_8)
        for (cmd in allCmds) {
            nexusCmd.testErr("$cmd -c $conf", "Could not decode private keys: Expected start of the object '{', but had 'EOF' instead at path: $\nJSON input: CORRUPTION")
        }
        // Unfinished client
        syncJsonToDisk(generateNewKeys(), clientKeysPath.toString())
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Cannot operate with unsubmitted client keys, run 'libeufin-nexus ebics-setup' first")
        }

        // Missing bank keys
        syncJsonToDisk(generateNewKeys().apply {
            submitted_hia = true
            submitted_ini = true
        }, clientKeysPath.toString())
        bankKeysPath.deleteIfExists()
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Cannot operate without bank keys. Missing '$bankKeysPath' file. run 'libeufin-nexus ebics-setup' first")
        }
        // Bad bank json
        bankKeysPath.writeText("CORRUPTION", Charsets.UTF_8)
        for (cmd in allCmds) {
            nexusCmd.testErr("$cmd -c $conf", "Could not decode bank keys: Expected start of the object '{', but had 'EOF' instead at path: $\nJSON input: CORRUPTION")
        }
        // Unfinished bank
        syncJsonToDisk(BankPublicKeysFile(
            bank_authentication_public_key = CryptoUtil.generateRsaKeyPair(2048).public,
            bank_encryption_public_key = CryptoUtil.generateRsaKeyPair(2048).public,
            accepted = false
        ), bankKeysPath.toString())
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Cannot operate with unaccepted bank keys, run 'libeufin-nexus ebics-setup' until accepting the bank keys")
        }
    }
}