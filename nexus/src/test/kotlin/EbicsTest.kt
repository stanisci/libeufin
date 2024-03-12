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

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import org.junit.Test
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.*
import kotlin.io.path.Path
import kotlin.io.path.writeBytes
import kotlin.test.*

class EbicsTest {
    // POSTs an EBICS message to the mock bank.  Tests
    // the main branches: unreachable bank, non-200 status
    // code, and 200.
    @Test
    fun postMessage() = conf { config ->
        assertFailsWith<EbicsError.Transport> {
            getMockedClient {
                respondError(HttpStatusCode.NotFound)
            }.postToBank("http://ignored.example.com/", ByteArray(0), "Test")
        }.run {
            assertEquals("Test: bank HTTP error: 404 Not Found", message)
        }
        assertFailsWith<EbicsError.Transport> {
            getMockedClient {
                throw Exception("Simulate failure")
            }.postToBank("http://ignored.example.com/", ByteArray(0), "Test")
        }.run {
            assertEquals("Test: failed to contact bank", message)
            assertEquals("Simulate failure", cause!!.message)
        }
        assertFailsWith<EbicsError.Protocol> {
            getMockedClient {
                respondOk("<ebics broken></ebics>")
            }.postToBank("http://ignored.example.com/", ByteArray(0), "Test")
        }.run {
            assertEquals("Test: invalid XML bank reponse", message)
            assertEquals("Attribute name \"broken\" associated with an element type \"ebics\" must be followed by the ' = ' character.", cause!!.message)
        }
        getMockedClient {
            respondOk("<ebics></ebics>")
        }.postToBank("http://ignored.example.com/", ByteArray(0), "Test")
    }

    // Tests that internal repr. of keys lead to valid PDF.
    // Mainly tests that the function does not throw any error.
    @Test
    fun keysPdf() = conf { config -> 
        val pdf = generateKeysPdf(clientKeys, config)
        Path("/tmp/libeufin-nexus-test-keys.pdf").writeBytes(pdf)
    }
}