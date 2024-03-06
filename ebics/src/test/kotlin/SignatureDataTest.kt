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
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.ebics.XMLUtil
import tech.libeufin.ebics.ebics_h004.EbicsRequest
import tech.libeufin.ebics.ebics_h004.EbicsTypes
import java.math.BigInteger
import java.util.*
import javax.xml.datatype.DatatypeFactory

class SignatureDataTest {

    @Test
    fun makeSignatureData() {

        val pair = CryptoUtil.generateRsaKeyPair(1024)

        val tmp = EbicsRequest().apply {
            header = EbicsRequest.Header().apply {
                version = "H004"
                revision = 1
                authenticate = true
                static = EbicsRequest.StaticHeaderType().apply {
                    hostID = "some host ID"
                    nonce = "nonce".toByteArray()
                    timestamp = DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
                    partnerID = "some partner ID"
                    userID = "some user ID"
                    orderDetails = EbicsRequest.OrderDetails().apply {
                        orderType = "TST"
                        orderAttribute = "OZHNN"
                    }
                    bankPubKeyDigests = EbicsRequest.BankPubKeyDigests().apply {
                        authentication = EbicsTypes.PubKeyDigest().apply {
                            algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            version = "X002"
                            value = CryptoUtil.getEbicsPublicKeyHash(pair.public)
                        }
                        encryption = EbicsTypes.PubKeyDigest().apply {
                            algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            version = "E002"
                            value = CryptoUtil.getEbicsPublicKeyHash(pair.public)
                        }
                    }
                    securityMedium = "0000"
                    numSegments = BigInteger.ONE

                    authSignature = SignatureType()
                }
                mutable = EbicsRequest.MutableHeader().apply {
                    transactionPhase = EbicsTypes.TransactionPhaseType.INITIALISATION
                }
                body = EbicsRequest.Body().apply {
                    dataTransfer = EbicsRequest.DataTransfer().apply {
                        signatureData = EbicsRequest.SignatureData().apply {
                            authenticate = true
                            value = "to byte array".toByteArray()
                        }
                        dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                            transactionKey = "mock".toByteArray()
                            authenticate = true
                            encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                                algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                                version = "E002"
                                value =
                                    CryptoUtil.getEbicsPublicKeyHash(pair.public)
                            }
                        }
                        hostId = "a host ID"
                    }
                }
            }
        }

        println(XMLUtil.convertJaxbToBytes(tmp))

    }
}