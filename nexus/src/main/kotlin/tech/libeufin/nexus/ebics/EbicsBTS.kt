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
import tech.libeufin.common.decodeBase64
import tech.libeufin.common.encodeBase64
import tech.libeufin.common.encodeHex
import tech.libeufin.common.encodeUpHex
import tech.libeufin.nexus.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


fun Instant.xmlDate(): String = 
    DateTimeFormatter.ISO_DATE.withZone(ZoneId.of("UTC")).format(this)
fun Instant.xmlDateTime(): String = 
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")).format(this)

/** EBICS protocol for business transactions */
class EbicsBTS(
    val cfg: NexusConfig, 
    val bankKeys: BankPublicKeysFile,
    val clientKeys: ClientPrivateKeysFile,
    val order: EbicsOrder
) {
    /* ----- Download ----- */

    fun downloadInitialization(startDate: Instant?, endDate: Instant?): ByteArray {
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
                        when (order) {
                            is EbicsOrder.V2_5 -> {
                                el("OrderType", order.type)
                                el("OrderAttribute", order.attribute)
                                el("StandardOrderParams") {
                                    if (startDate != null) {
                                        el("DateRange") {
                                            el("Start", startDate.xmlDate())
                                            el("End", (endDate ?: Instant.now()).xmlDate())
                                        }
                                    }
                                }
                            }
                            is EbicsOrder.V3 -> {
                                el("AdminOrderType", order.type)
                                if (order.type == "BTD") {
                                    el("BTDOrderParams") {
                                        el("Service") {
                                            el("ServiceName", order.name!!)
                                            el("Scope", order.scope!!)
                                            if (order.container != null) {
                                                el("Container") {
                                                    attr("containerType", order.container)
                                                }
                                            }
                                            el("MsgName") {
                                                attr("version", order.messageVersion!!)
                                                text(order.messageName!!)
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
        nbSegment: Int,
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
                        attr("lastSegment", if (nbSegment == segmentNumber) "true" else "false")
                        text(segmentNumber.toString())
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

    fun uploadInitialization(uploadData: PreparedUploadData): ByteArray {
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
                        when (order) {
                            is EbicsOrder.V2_5 -> {
                                // TODO
                            }
                            is EbicsOrder.V3 -> {
                                el("AdminOrderType", order.type)
                                el("BTUOrderParams") {
                                    el("Service") {
                                        el("ServiceName", order.name!!)
                                        el("Scope", order.scope!!)
                                        el("MsgName") {
                                            attr("version", order.messageVersion!!)
                                            text(order.messageName!!)
                                        }
                                    }
                                    el("SignatureFlag", "true")
                                }
                            }
                        }
                    }
                    bankDigest()
                    el("NumSegments", uploadData.segments.size.toString())
                    
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
                        el("TransactionKey", uploadData.transactionKey.encodeBase64())
                    }
                    el("SignatureData") {
                        attr("authenticate", "true")
                        text(uploadData.userSignatureDataEncrypted)
                    }
                    el("DataDigest") {
                        attr("SignatureVersion", "A006")
                        text(uploadData.dataDigest.encodeBase64())
                    }
                }
            }
        }
    }

    fun uploadTransfer(
        transactionId: String,
        uploadData: PreparedUploadData,
        segmentNumber: Int
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
                        attr("lastSegment", if (uploadData.segments.size == segmentNumber) "true" else "false")
                        text(segmentNumber.toString())
                    }
                }
            }
            el("AuthSignature")
            el("body/DataTransfer/OrderData", uploadData.segments[segmentNumber-1])
        }
    }

    /* ----- Helpers ----- */

    /** Generate a signed ebicsRequest */
    private fun signedRequest(lambda: XmlBuilder.() -> Unit): ByteArray  {
        val doc = XmlBuilder.toDom("ebicsRequest", "urn:org:ebics:${order.schema}") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:${order.schema}")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("Version", order.schema)
            attr("Revision", "1")
            lambda()
        }
        XMLUtil.signEbicsDocument(
            doc,
            clientKeys.authentication_private_key,
            order.schema
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