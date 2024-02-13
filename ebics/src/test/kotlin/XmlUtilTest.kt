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

import org.apache.xml.security.binding.xmldsig.SignatureType
import org.junit.Test
import org.junit.Assert.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.ebics.ebics_h004.EbicsKeyManagementResponse
import tech.libeufin.ebics.ebics_h004.EbicsResponse
import tech.libeufin.ebics.ebics_h004.EbicsTypes
import tech.libeufin.ebics.ebics_h004.HTDResponseOrderData
import tech.libeufin.common.CryptoUtil
import tech.libeufin.ebics.XMLUtil
import java.security.KeyPairGenerator
import java.util.*
import javax.xml.transform.stream.StreamSource
import tech.libeufin.ebics.XMLUtil.Companion.signEbicsResponse

class XmlUtilTest {

    @Test
    fun deserializeConsecutiveLists() {
        val tmp = XMLUtil.convertBytesToJaxb<HTDResponseOrderData>("""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <HTDResponseOrderData xmlns="urn:org:ebics:H004">
              <PartnerInfo>
                <AddressInfo>
                  <Name>Foo</Name>
                </AddressInfo>
                <BankInfo>
                  <HostID>host01</HostID>
                </BankInfo>
                <AccountInfo Currency="EUR" Description="ACCT" ID="acctid1">
                  <AccountNumber international="true">DE21500105174751659277</AccountNumber>
                  <BankCode international="true">INGDDEFFXXX</BankCode>
                  <AccountHolder>Mina Musterfrau</AccountHolder>
                </AccountInfo>
                <AccountInfo Currency="EUR" Description="glsdemoacct" ID="glsdemo">
                  <AccountNumber international="true">DE91430609670123123123</AccountNumber>
                  <BankCode international="true">GENODEM1GLS</BankCode>
                  <AccountHolder>Mina Musterfrau</AccountHolder>
                </AccountInfo>
                <OrderInfo>
                  <OrderType>C53</OrderType>
                  <TransferType>Download</TransferType>
                  <Description>foo</Description>
                </OrderInfo>
                <OrderInfo>
                  <OrderType>C52</OrderType>
                  <TransferType>Download</TransferType>
                  <Description>foo</Description>
                </OrderInfo>
                <OrderInfo>
                  <OrderType>CCC</OrderType>
                  <TransferType>Upload</TransferType>
                  <Description>foo</Description>
                </OrderInfo>
              </PartnerInfo>
              <UserInfo>
                <UserID Status="5">USER1</UserID>
                <Name>Some User</Name>
                <Permission>
                  <OrderTypes>C54 C53 C52 CCC</OrderTypes>
                </Permission>
              </UserInfo>
            </HTDResponseOrderData>""".trimIndent().toByteArray()
        )

        println(tmp.value.partnerInfo.orderInfoList[0].description)
    }

    @Test
    fun exceptionOnConversion() {
        try {
            XMLUtil.convertBytesToJaxb<EbicsKeyManagementResponse>("<malformed xml>".toByteArray())
        } catch (e: javax.xml.bind.UnmarshalException) {
            // just ensuring this is the exception
            println("caught")
            return
        }
        assertTrue(false)
    }

    @Test
    fun hevValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val hev = classLoader.getResourceAsStream("ebics_hev.xml")
        assertTrue(XMLUtil.validate(StreamSource(hev)))
    }

    @Test
    fun iniValidation(){
        val classLoader = ClassLoader.getSystemClassLoader()
        val ini = classLoader.getResourceAsStream("ebics_ini_request_sample.xml")
        assertTrue(XMLUtil.validate(StreamSource(ini)))
    }

    @Test
    fun basicSigningTest() {
        val doc = XMLUtil.parseBytesIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
            </myMessage>
        """.trimIndent().toByteArray())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        val otherPair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
        kotlin.test.assertFalse(XMLUtil.verifyEbicsDocument(doc, otherPair.public))
    }

    @Test
    fun verifySigningWithConversion() {

        val pair = CryptoUtil.generateRsaKeyPair(2048)

        val response = EbicsResponse().apply {
            version = "H004"
            header = EbicsResponse.Header().apply {
                _static = EbicsResponse.StaticHeaderType()
                mutable = EbicsResponse.MutableHeaderType().apply {
                    this.reportText = "foo"
                    this.returnCode = "bar"
                    this.transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
            }
            authSignature = SignatureType()
            body = EbicsResponse.Body().apply {
                returnCode = EbicsResponse.ReturnCode().apply {
                    authenticate = true
                    value = "asdf"
                }
            }
        }

        val signature = signEbicsResponse(response, pair.private)
        val signatureJaxb = XMLUtil.convertBytesToJaxb<EbicsResponse>(signature)

        assertTrue(
            XMLUtil.verifyEbicsDocument(
                XMLUtil.convertJaxbToDocument(signatureJaxb.value),
                pair.public
            )
        )
    }

    @Test
    fun multiAuthSigningTest() {
        val doc = XMLUtil.parseBytesIntoDom("""
            <myMessage xmlns:ebics="urn:org:ebics:H004">
                <ebics:AuthSignature />
                <foo authenticate="true">Hello World</foo>
                <bar authenticate="true">Another one!</bar>
            </myMessage>
        """.trimIndent().toByteArray())
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.genKeyPair()
        XMLUtil.signEbicsDocument(doc, pair.private)
        kotlin.test.assertTrue(XMLUtil.verifyEbicsDocument(doc, pair.public))
    }

    @Test
    fun testRefSignature() {
        val classLoader = ClassLoader.getSystemClassLoader()
        val docText = classLoader.getResourceAsStream("signature1/doc.xml")!!.readAllBytes()
        val doc = XMLUtil.parseBytesIntoDom(docText)
        val keyText = classLoader.getResourceAsStream("signature1/public_key.txt")!!.readAllBytes()
        val keyBytes = Base64.getDecoder().decode(keyText)
        val key = CryptoUtil.loadRsaPublicKey(keyBytes)
        assertTrue(XMLUtil.verifyEbicsDocument(doc, key))
    }
}