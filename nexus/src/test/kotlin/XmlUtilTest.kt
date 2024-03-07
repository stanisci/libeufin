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

import org.junit.Assert.assertTrue
import org.junit.Test
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.common.decodeBase64
import tech.libeufin.nexus.XMLUtil
import java.security.KeyPairGenerator
import javax.xml.transform.stream.StreamSource

class XmlUtilTest {

    @Test
    fun basicSigningTest() {
        val doc = XMLUtil.parseIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
            </myMessage>
        """.trimIndent().toByteArray().inputStream())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        val otherPair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
        kotlin.test.assertFalse(XMLUtil.verifyEbicsDocument(doc, otherPair.public))
    }

    @Test
    fun multiAuthSigningTest() {
        val doc = XMLUtil.parseIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
                <bar authenticate="true">Another one!</bar>
            </myMessage>
        """.trimIndent().toByteArray().inputStream())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
    }

    @Test
    fun testRefSignature() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val docText = classLoader.getResourceAsStream("signature1/doc.xml")
        val doc = XMLUtil.parseIntoDom(docText)
        val keyStream = classLoader.getResourceAsStream("signature1/public_key.txt")
        val keyBytes = keyStream.decodeBase64().readAllBytes()
        val key = CryptoUtil.loadRsaPublicKey(keyBytes)
        assertTrue(XMLUtil.verifyEbicsDocument(doc, key))
    }
}