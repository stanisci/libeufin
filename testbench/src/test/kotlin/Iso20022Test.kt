/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

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

import org.junit.Test
import tech.libeufin.nexus.parseCustomerAck
import tech.libeufin.nexus.parseCustomerPaymentStatusReport
import tech.libeufin.nexus.parseTx
import tech.libeufin.nexus.loadConfig
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class Iso20022Test {
    @Test
    fun sample() {
        for (sample in Path("sample").listDirectoryEntries()) {
            val content = Files.newInputStream(sample)
            val name = sample.toString()
            println(name)
            if (name.contains("HAC")) {
                parseCustomerAck(content)
            } else if (name.contains("pain.002") || name.contains("pain002") ) {
                parseCustomerPaymentStatusReport(content)
            } else {
                parseTx(content, "CHF")
            }
        }
    }

    @Test
    fun logs() {
        val root = Path("test")
        if (!root.exists()) return
        for (platform in root.listDirectoryEntries()) {
            if (!platform.isDirectory()) continue
            for (file in platform.listDirectoryEntries()) {
                if (!file.isDirectory()) continue
                val fetch = file.resolve("fetch")
                if (!fetch.exists()) continue
                val cfg = loadConfig(platform.resolve("ebics.conf"))
                val currency = cfg.requireString("nexus-ebics", "currency")
                for (log in fetch.listDirectoryEntries()) {
                    val content = Files.newInputStream(log)
                    val name = log.toString()
                    println(name)
                    if (name.contains("HAC")) {
                        parseCustomerAck(content)
                    } else if (name.contains("pain.002")) {
                        parseCustomerPaymentStatusReport(content)
                    } else if (!name.contains("camt.052") && !name.contains("_C52_") && !name.contains("_Z01_")) {
                        parseTx(content, currency)
                    }
                }
            }
        }
    }
}