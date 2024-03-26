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
class EbicsKeyMng(
    private val cfg: EbicsSetupConfig,
    private val clientKeys: ClientPrivateKeysFile,
    private val ebics3: Boolean
) {
    private val schema = if (ebics3) "H005" else "H004"
    fun INI(): ByteArray {
        val data = XMLOrderData(cfg, "SignaturePubKeyOrderData", "http://www.ebics.org/S00${if (ebics3) 2 else 1}") {
            el("SignaturePubKeyInfo") {
                RSAKeyXml(clientKeys.signature_private_key)
                el("SignatureVersion", "A006")
            }
        }
        return request("ebicsUnsecuredRequest", "INI", "0200", data)
    }

    fun HIA(): ByteArray {
        val data = XMLOrderData(cfg, "HIARequestOrderData", "urn:org:ebics:$schema") {
            el("AuthenticationPubKeyInfo") {
                RSAKeyXml(clientKeys.authentication_private_key)
                el("AuthenticationVersion", "X002")
            }
            el("EncryptionPubKeyInfo") {
                RSAKeyXml(clientKeys.encryption_private_key)
                el("EncryptionVersion", "E002")
            }
        }
        return request("ebicsUnsecuredRequest", "HIA", "0200", data)
    }

    fun HPB(): ByteArray {
        val nonce = getNonce(128)
        return request("ebicsNoPubKeyDigestsRequest", "HPB", "0000", timestamp = Instant.now(), sign = true)
    }

    /* ----- Helpers ----- */

    private fun request(
        name: String,
        order: String,
        securityMedium: String,
        data: String? = null,
        timestamp: Instant? = null,
        sign: Boolean = false
    ): ByteArray {
        val doc = XmlBuilder.toDom(name, "urn:org:ebics:$schema") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:$schema")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", "$schema")
            attr("Revision", "1")
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    if (timestamp != null) {
                        el("Nonce", getNonce(128).encodeUpHex())
                        el("Timestamp", timestamp.xmlDateTime())
                    }
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    el("OrderDetails") {
                        if (ebics3) {
                            el("AdminOrderType", order)
                        } else {
                            el("OrderType", order)
                            el("OrderAttribute", if (order == "HPB") "DZHNN" else "DZNNN")
                        }
                    }
                    el("SecurityMedium", securityMedium)
                }
                el("mutable")
            }
            if (sign) el("AuthSignature")
            el("body") {
                if (data != null) el("DataTransfer/OrderData", data)
            }
        }
        if (sign) XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key, schema)
        return XMLUtil.convertDomToBytes(doc)
    }

    private fun XmlBuilder.RSAKeyXml(key: RSAPrivateCrtKey) {
        if (ebics3) {
            val cert = CryptoUtil.certificateFromPrivate(key)
            el("ds:X509Data") {
                el("ds:X509Certificate", cert.encoded.encodeBase64())
            }
        } else {
            el("PubKeyValue") {
                el("ds:RSAKeyValue") {
                    el("ds:Modulus", key.modulus.encodeBase64())
                    el("ds:Exponent", key.publicExponent.encodeBase64())
                }
            }
        }
    }
    
    private fun XMLOrderData(cfg: EbicsSetupConfig, name: String, schema: String, build: XmlBuilder.() -> Unit): String {
        return XmlBuilder.toBytes(name) {
            attr("xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("xmlns", schema)
            build()
            el("PartnerID", cfg.ebicsPartnerId)
            el("UserID", cfg.ebicsUserId)
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
            fun XmlDestructor.rsaPubKey(): RSAPublicKey {
                val cert = opt("X509Data")?.one("X509Certificate")?.text()?.decodeBase64()
                return if (cert != null) {
                    CryptoUtil.loadRsaPublicKeyFromCertificate(cert)
                } else {
                    one("PubKeyValue").one("RSAKeyValue") {
                        CryptoUtil.loadRsaPublicKeyFromComponents(
                            one("Modulus").text().decodeBase64(),
                            one("Exponent").text().decodeBase64(),
                        )
                    }
                    
                }
            }
            return XmlDestructor.fromStream(data, "HPBResponseOrderData") {
                val authPub = one("AuthenticationPubKeyInfo") {
                    val version = one("AuthenticationVersion").text()
                    require(version == "X002") { "Expected authentication version X002 got unsupported $version" }
                    rsaPubKey()
                }
                val encPub = one("EncryptionPubKeyInfo") {
                    val version = one("EncryptionVersion").text()
                    require(version == "E002") { "Expected encryption version E002 got unsupported $version" }
                    rsaPubKey()
                }
                Pair(authPub, encPub)
            }
        }
    }
}