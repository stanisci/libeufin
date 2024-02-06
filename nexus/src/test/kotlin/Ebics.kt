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

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.ebics.XMLUtil
import tech.libeufin.ebics.ebics_h004.EbicsUnsecuredRequest
import kotlin.test.*
import kotlin.io.path.*

class Ebics {
    // Checks XML is valid and INI.
    @Test
    fun iniMessage() = conf { config -> 
        val msg = generateIniMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "INI") // ensures is INI
    }

    // Checks XML is valid and HIA.
    @Test
    fun hiaMessage() = conf { config -> 
        val msg = generateHiaMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "HIA") // ensures is HIA
    }

    // Checks XML is valid and HPB.
    @Test
    fun hpbMessage() = conf { config -> 
        val msg = generateHpbMessage(config, clientKeys)
        val ini = XMLUtil.convertStringToJaxb<EbicsUnsecuredRequest>(msg) // ensures is valid
        assertEquals(ini.value.header.static.orderDetails.orderType, "HPB") // ensures is HPB
    }
    // POSTs an EBICS message to the mock bank.  Tests
    // the main branches: unreachable bank, non-200 status
    // code, and 200.
    @Test
    fun postMessage() = conf { config -> 
        val client404 = getMockedClient {
            respondError(HttpStatusCode.NotFound)
        }
        val clientNoResponse = getMockedClient {
            throw Exception("Network issue.")
        }
        val clientOk = getMockedClient {
            respondOk("Not EBICS anyway.")
        }
        assertNull(client404.postToBank("http://ignored.example.com/", "ignored"))
        assertNull(clientNoResponse.postToBank("http://ignored.example.com/", "ignored"))
        assertNotNull(clientOk.postToBank("http://ignored.example.com/", "ignored"))
    }

    // Tests that internal repr. of keys lead to valid PDF.
    // Mainly tests that the function does not throw any error.
    @Test
    fun keysPdf() = conf { config -> 
        val pdf = generateKeysPdf(clientKeys, config)
        Path("/tmp/libeufin-nexus-test-keys.pdf").writeBytes(pdf)
    }
}