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

import io.ktor.client.*
import tech.libeufin.nexus.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import java.math.BigInteger
import java.time.*
import java.time.format.*
import java.util.*
import java.io.File
import org.w3c.dom.*
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.datatype.DatatypeFactory
import java.security.interfaces.*


fun Instant.xmlDate(): String = DateTimeFormatter.ISO_DATE.withZone(ZoneId.of("UTC")).format(this)
fun Instant.xmlDateTime(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")).format(this)

// TODO WIP
fun iniRequest(
    cfg: EbicsSetupConfig, 
    clientKeys: ClientPrivateKeysFile
): ByteArray {
    val temp = XmlBuilder.toBytes("ns2:SignaturePubKeyOrderData") {
        attr("xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
        attr("xmlns:ns2", "http://www.ebics.org/S001")
        el("ns2:SignaturePubKeyInfo") {
            el("ns2:PubKeyValue") {
                el("ds:RSAKeyValue") {
                    el("ds:Modulus", clientKeys.signature_private_key.modulus.encodeBase64())
                    el("ds:Exponent", clientKeys.signature_private_key.publicExponent.encodeBase64())
                }
            }
            el("ns2:SignatureVersion", "A006")
        }
        el("ns2:PartnerID", cfg.ebicsPartnerId)
        el("ns2:UserID", cfg.ebicsUserId)
    }
    // TODO in ebics:H005 we MUST use x509 certificates ...
    println(temp)
    val inner = temp.inputStream().deflate().encodeBase64()
    val doc = XmlBuilder.toDom("ebicsUnsecuredRequest", "urn:org:ebics:H005") {
        attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H005")
        attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
        attr("Version", "H005")
        attr("Revision", "1")
        el("header") {
            attr("authenticate", "true")
            el("static") {
                el("HostID", cfg.ebicsHostId)
                el("PartnerID", cfg.ebicsPartnerId)
                el("UserID", cfg.ebicsUserId)
                el("OrderDetails/AdminOrderType", "INI")
                el("SecurityMedium", "0200")
            }
            el("mutable")
        }
        el("body/DataTransfer/OrderData", inner)
    }
    return XMLUtil.convertDomToBytes(doc)
}

/** EBICS 3 protocol for business transactions */
class Ebics3BTS(
    private val cfg: EbicsSetupConfig, 
    private val bankKeys: BankPublicKeysFile,
    private val clientKeys: ClientPrivateKeysFile
) {
    /* ----- Download ----- */

    fun downloadInitialization(orderType: String, service: Ebics3Service?, startDate: Instant?, endDate: Instant?): ByteArray {
        val nonce = getNonce(128)
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("Nonce", nonce.encodeHex())
                    el("Timestamp", Instant.now().xmlDateTime())
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    // SystemID
                    // Product
                    el("OrderDetails") {
                        el("AdminOrderType", orderType)
                        if (orderType == "BTD") {
                            el("BTDOrderParams") {
                                if (service != null) {
                                    el("Service") {
                                        el("ServiceName", service.name)
                                        el("Scope", service.scope)
                                        if (service.container != null) {
                                            el("Container") {
                                                attr("containerType", service.container)
                                            }
                                        }
                                        el("MsgName") {
                                            attr("version", service.messageVersion)
                                            text(service.messageName)
                                        }
                                    }
                                }
                                if (startDate != null) {
                                    el("DateRange") {
                                        el("Start", startDate.xmlDate())
                                        el("End", (endDate ?: Instant.now()).xmlDate())
                                    }
                                }
                            }
                        }
                    }
                    bankDigest()
                }
                el("mutable/TransactionPhase", "Initialisation")
            }
            el("AuthSignature")
            el("body")
        }
    }

    fun downloadTransfer(
        howManySegments: Int,
        segmentNumber: Int,
        transactionId: String
    ): ByteArray {
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("TransactionID", transactionId)
                }
                el("mutable") {
                    el("TransactionPhase", "Transfer")
                    el("SegmentNumber") {
                        attr("lastSegment", if (howManySegments == segmentNumber) "true" else "false")
                    }
                }
            }
            el("AuthSignature")
            el("body")
        }
    }

    fun downloadReceipt(
        transactionId: String,
        success: Boolean
    ): ByteArray {
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("TransactionID", transactionId)
                }
                el("mutable") {
                    el("TransactionPhase", "Receipt")
                }
            }
            el("AuthSignature")
            el("body/TransferReceipt") {
                attr("authenticate", "true")
                el("ReceiptCode", if (success) "0" else "1")
            }
        }
    }

    /* ----- Upload ----- */

    fun uploadInitialization(service: Ebics3Service, preparedUploadData: PreparedUploadData): ByteArray {
        val nonce = getNonce(128)
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("Nonce", nonce.encodeUpHex())
                    el("Timestamp", Instant.now().xmlDateTime())
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    // SystemID
                    // Product
                    el("OrderDetails") {
                        el("AdminOrderType", "BTU")
                        el("BTUOrderParams") {
                            el("Service") {
                                el("ServiceName", service.name)
                                el("Scope", service.scope)
                                el("MsgName") {
                                    attr("version", service.messageVersion)
                                    text(service.messageName)
                                }
                            }
                            el("SignatureFlag", "true")
                        }
                    }
                    bankDigest()
                    el("NumSegments", "1") // TODO test upload of many segment
                    
                }
                el("mutable") {
                    el("TransactionPhase", "Initialisation")
                }
            }
            el("AuthSignature")
            el("body") {
                el("DataTransfer") {
                    el("DataEncryptionInfo") {
                        attr("authenticate", "true")
                        el("EncryptionPubKeyDigest") {
                            attr("Version", "E002")
                            attr("Algorithm", "http://www.w3.org/2001/04/xmlenc#sha256")
                            text(CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_encryption_public_key).encodeBase64())
                        }
                        el("TransactionKey", preparedUploadData.transactionKey.encodeBase64())
                    }
                    el("SignatureData") {
                        attr("authenticate", "true")
                        text(preparedUploadData.userSignatureDataEncrypted.encodeBase64())
                    }
                    el("DataDigest") {
                        attr("SignatureVersion", "A006")
                        text(preparedUploadData.dataDigest.encodeBase64())
                    }
                }
            }
        }
    }

    fun uploadTransfer(
        transactionId: String,
        uploadData: PreparedUploadData
    ): ByteArray {
        val chunkIndex = 1 // TODO test upload of many segment
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("TransactionID", transactionId)
                }
                el("mutable") {
                    el("TransactionPhase", "Transfer")
                    el("SegmentNumber") {
                        attr("lastSegment", "true")
                        text(chunkIndex.toString())
                    }
                }
            }
            el("AuthSignature")
            el("body/DataTransfer/OrderData", uploadData.encryptedPayloadChunks[chunkIndex - 1])
        }
    }

    /* ----- Helpers ----- */

    /** Generate a signed H005 ebicsRequest */
    private fun signedRequest(lambda: XmlBuilder.() -> Unit): ByteArray  {
        val doc = XmlBuilder.toDom("ebicsRequest", "urn:org:ebics:H005") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H005")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", "H005")
            attr("Revision", "1")
            lambda()
        }
        XMLUtil.signEbicsDocument(
            doc,
            clientKeys.authentication_private_key,
            withEbics3 = true
        )
        return XMLUtil.convertDomToBytes(doc)
    }

    private fun XmlBuilder.bankDigest() {
        el("BankPubKeyDigests") {
            el("Authentication") {
                attr("Version", "X002")
                attr("Algorithm", "http://www.w3.org/2001/04/xmlenc#sha256")
                text(CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_authentication_public_key).encodeBase64())
            }
            el("Encryption") {
                attr("Version", "E002")
                attr("Algorithm", "http://www.w3.org/2001/04/xmlenc#sha256")
                text(CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_encryption_public_key).encodeBase64())
            }
            // Signature
        }
        el("SecurityMedium", "0000")
    }

    companion object {
        fun parseResponse(doc: Document): EbicsResponse<BTSResponse> {
            return XmlDestructor.fromDoc(doc, "ebicsResponse") {
                var transactionID: String? = null
                var numSegments: Int? = null
                lateinit var technicalCode: EbicsReturnCode
                lateinit var bankCode: EbicsReturnCode
                var orderID: String? = null
                var segmentNumber: Int? = null
                var payloadChunk: ByteArray? = null
                var dataEncryptionInfo: DataEncryptionInfo? = null
                one("header") {
                    one("static") {
                        transactionID = opt("TransactionID")?.text()
                        numSegments = opt("NumSegments")?.text()?.toInt()
                    }
                    one("mutable") {
                        segmentNumber = opt("SegmentNumber")?.text()?.toInt()
                        orderID = opt("OrderID")?.text()
                        technicalCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                    }
                }
                one("body") {
                    opt("DataTransfer") {
                        payloadChunk = one("OrderData").text().decodeBase64()
                        dataEncryptionInfo = opt("DataEncryptionInfo") {
                            DataEncryptionInfo(
                                one("TransactionKey").text().decodeBase64(),
                                one("EncryptionPubKeyDigest").text().decodeBase64()
                            )
                        }
                    }
                    bankCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                }
                EbicsResponse(
                    bankCode = bankCode,
                    technicalCode = technicalCode,
                    content = BTSResponse(
                        transactionID = transactionID,
                        orderID = orderID,
                        payloadChunk = payloadChunk,
                        dataEncryptionInfo = dataEncryptionInfo,
                        numSegments = numSegments,
                        segmentNumber = segmentNumber
                    )
                )
            }
        }
    }
}

data class BTSResponse(
    val transactionID: String?,
    val orderID: String?,
    val dataEncryptionInfo: DataEncryptionInfo?,
    val payloadChunk: ByteArray?,
    val segmentNumber: Int?,
    val numSegments: Int?
)