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

package tech.libeufin.nexus.ebics

import org.w3c.dom.Document
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.common.*
import tech.libeufin.nexus.*
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.util.*
import javax.xml.datatype.DatatypeFactory
import java.security.interfaces.*

/** EBICS protocol for key management */
class Ebics3KeyMng(
    private val cfg: EbicsSetupConfig,
    private val clientKeys: ClientPrivateKeysFile
) {
    fun INI(): ByteArray {
        val inner = XMLOrderData(cfg, "ns2:SignaturePubKeyOrderData", "http://www.ebics.org/S001") {
            el("ns2:SignaturePubKeyInfo") {
                RSAKeyXml(clientKeys.signature_private_key)
                el("ns2:SignatureVersion", "A006")
            }
        }
        val doc = XmlBuilder.toDom("ebicsUnsecuredRequest", "urn:org:ebics:H004") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H004")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", "H004")
            attr("Revision", "1")
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    el("OrderDetails") {
                        el("OrderType", "INI")
                        el("OrderAttribute", "DZNNN")
                    }
                    el("SecurityMedium", "0200")
                }
                el("mutable")
            }
            el("body/DataTransfer/OrderData", inner)
        }
        return XMLUtil.convertDomToBytes(doc)
    }

    fun HIA(): ByteArray {
        val inner = XMLOrderData(cfg, "ns2:HIARequestOrderData", "urn:org:ebics:H004") {
            el("ns2:AuthenticationPubKeyInfo") {
                RSAKeyXml(clientKeys.authentication_private_key)
                el("ns2:AuthenticationVersion", "X002")
            }
            el("ns2:EncryptionPubKeyInfo") {
                RSAKeyXml(clientKeys.encryption_private_key)
                el("ns2:EncryptionVersion", "E002")
            }
        }
        val doc = XmlBuilder.toDom("ebicsUnsecuredRequest", "urn:org:ebics:H004") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H004")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", "H004")
            attr("Revision", "1")
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    el("OrderDetails") {
                        el("OrderType", "HIA")
                        el("OrderAttribute", "DZNNN")
                    }
                    el("SecurityMedium", "0200")
                }
                el("mutable")
            }
            el("body/DataTransfer/OrderData", inner)
        }
        return XMLUtil.convertDomToBytes(doc)
    }

    fun HPB(): ByteArray {
        val nonce = getNonce(128)
        val doc = XmlBuilder.toDom("ebicsNoPubKeyDigestsRequest", "urn:org:ebics:H004") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H004")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", "H004")
            attr("Revision", "1")
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("Nonce", nonce.encodeUpHex())
                    el("Timestamp", Instant.now().xmlDateTime())
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    el("OrderDetails") {
                        el("OrderType", "HPB")
                        el("OrderAttribute", "DZHNN")
                    }
                    el("SecurityMedium", "0000")
                }
                el("mutable")
            }
            el("AuthSignature")
            el("body")
        }
        XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key, "H004")
        return XMLUtil.convertDomToBytes(doc)
    }

    /* ----- Helpers ----- */

    private fun XmlBuilder.RSAKeyXml(key: RSAPrivateCrtKey) {
        el("ns2:PubKeyValue") {
            el("ds:RSAKeyValue") {
                el("ds:Modulus", key.modulus.encodeBase64())
                el("ds:Exponent", key.publicExponent.encodeBase64())
            }
        }
    }
    
    private fun XMLOrderData(cfg: EbicsSetupConfig, name: String, schema: String, build: XmlBuilder.() -> Unit): String {
        return XmlBuilder.toBytes(name) {
            attr("xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("xmlns:ns2", schema)
            build()
            el("ns2:PartnerID", cfg.ebicsPartnerId)
            el("ns2:UserID", cfg.ebicsUserId)
        }.inputStream().deflate().encodeBase64()
    }

    companion object {
        fun parseResponse(doc: Document, clientEncryptionKey: RSAPrivateCrtKey): EbicsResponse<InputStream?> {
            return XmlDestructor.fromDoc(doc, "ebicsKeyManagementResponse") {
                lateinit var technicalCode: EbicsReturnCode
                lateinit var bankCode: EbicsReturnCode
                var payload: InputStream? = null
                one("header") {
                    one("mutable") {
                        technicalCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                    }
                }
                one("body") {
                    bankCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                    payload = opt("DataTransfer") {
                        val descriptionInfo = one("DataEncryptionInfo") {
                            DataEncryptionInfo(
                                one("TransactionKey").text().decodeBase64(),
                                one("EncryptionPubKeyDigest").text().decodeBase64()
                            )
                        }
                        val chunk = one("OrderData").text().decodeBase64()
                        decryptAndDecompressPayload(
                            clientEncryptionKey,
                            descriptionInfo,
                            listOf(chunk)
                        )
                    }
                }
                EbicsResponse(
                    technicalCode = technicalCode, 
                    bankCode, 
                    content = payload
                )
            }
        }

        fun parseHpbOrder(data: InputStream): Pair<RSAPublicKey, RSAPublicKey> {
            return XmlDestructor.fromStream(data, "HPBResponseOrderData") {
                val authPub = one("AuthenticationPubKeyInfo").one("PubKeyValue").one("RSAKeyValue") {
                    CryptoUtil.loadRsaPublicKeyFromComponents(
                        one("Modulus").text().decodeBase64(),
                        one("Exponent").text().decodeBase64(),
                    )
                }
                val encPub = one("EncryptionPubKeyInfo").one("PubKeyValue").one("RSAKeyValue") {
                    CryptoUtil.loadRsaPublicKeyFromComponents(
                        one("Modulus").text().decodeBase64(),
                        one("Exponent").text().decodeBase64(),
                    )
                }
                Pair(authPub, encPub)
            }
        }
    }
}