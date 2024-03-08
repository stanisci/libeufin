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

/**
 * This file contains helpers to construct EBICS 2.x requests.
 */

package tech.libeufin.nexus.ebics

import org.slf4j.Logger
import org.slf4j.LoggerFactory
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

private val logger: Logger = LoggerFactory.getLogger("libeufin-nexus-ebics2")

/**
 * Parses the raw XML that came from the bank into the Nexus representation.
 *
 * @param clientEncryptionKey client private encryption key, used to decrypt
 *                            the transaction key.
 * @param xml the bank raw XML response
 * @return the internal representation of the XML response, or null if the parsing or the decryption failed.
 *         Note: it _is_ possible to successfully return the internal repr. of this response, where
 *         the payload is null.  That's however still useful, because the returned type provides bank
 *         and EBICS return codes.
 */
fun parseKeysMgmtResponse(
    clientEncryptionKey: RSAPrivateCrtKey,
    xml: Document
): EbicsKeyManagementResponseContent {
    return XmlDestructor.fromDoc(xml, "ebicsKeyManagementResponse") {
        lateinit var technicalReturnCode: EbicsReturnCode
        lateinit var bankReturnCode: EbicsReturnCode
        lateinit var reportText: String
        var payload: ByteArray? = null
        one("header") {
            one("mutable") {
                technicalReturnCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                reportText = one("ReportText").text()
            }
        }
        one("body") {
            bankReturnCode = EbicsReturnCode.lookup(one("ReturnCode").text())
            payload = opt("DataTransfer") {
                val descriptionInfo = one("DataEncryptionInfo") {
                    DataEncryptionInfo(
                        one("TransactionKey").text().decodeBase64(),
                        one("EncryptionPubKeyDigest").text().decodeBase64()
                    )
                }
                decryptAndDecompressPayload(
                    clientEncryptionKey,
                    descriptionInfo,
                    listOf(one("OrderData").text())
                ).readBytes()
            }
        }
        EbicsKeyManagementResponseContent(technicalReturnCode, bankReturnCode, payload)
    }
}

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

/**
 * Generates the INI message to upload the signature key.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS INI message.
 */
fun generateIniMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
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

/**
 * Generates the HIA message: uploads the authentication and
 * encryption keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HIA message.
 */
fun generateHiaMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
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

/**
 * Generates the HPB message: downloads the bank keys.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS HPB message.
 */
fun generateHpbMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
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
    XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key)
    return XMLUtil.convertDomToBytes(doc)
}

class HpbResponseData(
    val hostID: String,
    val encryptionPubKey: RSAPublicKey,
    val encryptionVersion: String,
    val authenticationPubKey: RSAPublicKey,
    val authenticationVersion: String
)

fun parseEbicsHpbOrder(orderDataRaw: InputStream): HpbResponseData {
    return XmlDestructor.fromStream(orderDataRaw, "HPBResponseOrderData") {
        val (authenticationPubKey, authenticationVersion) = one("AuthenticationPubKeyInfo") {
            Pair(
                one("PubKeyValue").one("RSAKeyValue") {
                    CryptoUtil.loadRsaPublicKeyFromComponents(
                        one("Modulus").text().decodeBase64(),
                        one("Exponent").text().decodeBase64(),
                    )
                },
                one("AuthenticationVersion").text()
            )
        }
        val (encryptionPubKey, encryptionVersion) = one("EncryptionPubKeyInfo") {
            Pair(
                one("PubKeyValue").one("RSAKeyValue") {
                    CryptoUtil.loadRsaPublicKeyFromComponents(
                        one("Modulus").text().decodeBase64(),
                        one("Exponent").text().decodeBase64(),
                    )
                },
                one("EncryptionVersion").text()
            )

        }
        val hostID: String = one("HostID").text()
        HpbResponseData(
            hostID = hostID,
            encryptionPubKey = encryptionPubKey,
            encryptionVersion = encryptionVersion,
            authenticationPubKey = authenticationPubKey,
            authenticationVersion = authenticationVersion
        )
    }
}

data class EbicsKeyManagementResponseContent(
    val technicalReturnCode: EbicsReturnCode,
    val bankReturnCode: EbicsReturnCode?,
    val orderData: ByteArray?
)