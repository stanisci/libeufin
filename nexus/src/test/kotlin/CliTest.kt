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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.testing.test
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.nexus.*
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.*
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val cfg = loadConfig(Path(conf))
        val clientKeysPath = cfg.requirePath("nexus-ebics", "client_private_keys_file")
        val bankKeysPath = cfg.requirePath("nexus-ebics", "bank_public_keys_file")
        clientKeysPath.parent!!.createDirectories()
        clientKeysPath.parent!!.toFile().setWritable(true)
        bankKeysPath.parent!!.createDirectories()
        
        // Missing client keys
        clientKeysPath.deleteIfExists()
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Missing client private keys file at '$clientKeysPath', run 'libeufin-nexus ebics-setup' first")
        }
        // Bad client json
        clientKeysPath.writeText("CORRUPTION", Charsets.UTF_8)
        for (cmd in allCmds) {
            nexusCmd.testErr("$cmd -c $conf", "Could not decode client private keys at '$clientKeysPath': Expected start of the object '{', but had 'EOF' instead at path: $\nJSON input: CORRUPTION")
        }
        // Missing permission
        clientKeysPath.toFile().setReadable(false)
        if (!clientKeysPath.isReadable()) { // Skip if root
            for (cmd in allCmds) {
                nexusCmd.testErr("$cmd -c $conf", "Could not read client private keys at '$clientKeysPath': permission denied")
            }
        }
        // Unfinished client
        persistClientKeys(generateNewKeys(), clientKeysPath)
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Unsubmitted client private keys, run 'libeufin-nexus ebics-setup' first")
        }

        // Missing bank keys
        persistClientKeys(generateNewKeys().apply {
            submitted_hia = true
            submitted_ini = true
        }, clientKeysPath)
        bankKeysPath.deleteIfExists()
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Missing bank public keys at '$bankKeysPath', run 'libeufin-nexus ebics-setup' first")
        }
        // Bad bank json
        bankKeysPath.writeText("CORRUPTION", Charsets.UTF_8)
        for (cmd in allCmds) {
            nexusCmd.testErr("$cmd -c $conf", "Could not decode bank public keys at '$bankKeysPath': Expected start of the object '{', but had 'EOF' instead at path: $\nJSON input: CORRUPTION")
        }
        // Missing permission
        bankKeysPath.toFile().setReadable(false)
        if (!bankKeysPath.isReadable()) { // Skip if root
            for (cmd in allCmds) {
                nexusCmd.testErr("$cmd -c $conf", "Could not read bank public keys at '$bankKeysPath': permission denied")
            }
        }
        // Unfinished bank
        persistBankKeys(BankPublicKeysFile(
            bank_authentication_public_key = CryptoUtil.generateRsaKeyPair(2048).public,
            bank_encryption_public_key = CryptoUtil.generateRsaKeyPair(2048).public,
            accepted = false
        ), bankKeysPath)
        for (cmd in cmds) {
            nexusCmd.testErr("$cmd -c $conf", "Unaccepted bank public keys, run 'libeufin-nexus ebics-setup' until accepting the bank keys")
        }

        // Missing permission
        clientKeysPath.deleteIfExists()
        clientKeysPath.parent!!.toFile().setWritable(false)
        if (!clientKeysPath.parent!!.isWritable()) { // Skip if root
            nexusCmd.testErr("ebics-setup -c $conf", "Could not write client private keys at '$clientKeysPath': permission denied on '${clientKeysPath.parent}'")
        }
    }
}