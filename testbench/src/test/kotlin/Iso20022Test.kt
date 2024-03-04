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

import tech.libeufin.nexus.*
import org.junit.Test
import java.nio.file.*
import kotlin.io.path.*

class Iso20022Test {
    @Test
    fun sample() {
        for (sample in Path("sample").listDirectoryEntries()) {
            val content = Files.newInputStream(sample)
            val name = sample.toString()
            println(name)
            if (name.contains("HAC")) {
                parseCustomerAck(content)
            } else if (name.contains("pain.002")) {
                parseCustomerPaymentStatusReport(content)
            } else {
                parseTxNotif(content, "CHF", mutableListOf(), mutableListOf())
            }
        }
    }

    @Test
    fun logs() {
        val root = Path("test")
        if (!root.exists()) return;
        for (platform in root.listDirectoryEntries()) {
            for (file in platform.listDirectoryEntries()) {
                val fetch = file.resolve("fetch")
                if (file.isDirectory() && fetch.exists()) {
                    for (log in fetch.listDirectoryEntries()) {
                        val content = Files.newInputStream(log)
                        val name = log.toString()
                        println(name)
                        if (name.contains("HAC")) {
                            parseCustomerAck(content)
                        } else if (name.contains("pain.002")) {
                            parseCustomerPaymentStatusReport(content)
                        } else {
                            parseTxNotif(content, "CHF", mutableListOf(), mutableListOf())
                        }
                    }
                }
            }
        }
    }
}