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
import tech.libeufin.ebics.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import tech.libeufin.ebics.ebics_h005.Ebics3Request
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.nexus.logger
import java.math.BigInteger
import java.time.*
import java.time.format.*
import java.util.*
import java.io.File
import org.w3c.dom.*
import javax.xml.datatype.XMLGregorianCalendar
import javax.xml.datatype.DatatypeFactory


//fun String.toDate(): LocalDate = LocalDate.parse(this, DateTimeFormatter.ISO_DATE)
//fun String.toDateTime(): LocalDateTime = LocalDateTime.parse(this, DateTimeFormatter.ISO_DATE_TIME)
//fun String.toYearMonth(): YearMonth = YearMonth.parse(this, DateTimeFormatter.ISO_DATE)
//fun String.toYear(): Year = Year.parse(this, DateTimeFormatter.ISO_DATE)

fun Instant.xmlDate(): String = DateTimeFormatter.ISO_DATE.withZone(ZoneId.of("UTC")).format(this)
fun Instant.xmlDateTime(): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.of("UTC")).format(this)

class Ebics3Impl(
    private val cfg: EbicsSetupConfig, 
    private val bankKeys: BankPublicKeysFile,
    private val clientKeys: ClientPrivateKeysFile
) {

    private fun signedRequest(lambda: XmlBuilder.() -> Unit): ByteArray  {
        val doc = XmlBuilder.toDom("ebicsRequest", "urn:org:ebics:H005") {
            attr("http://www.w3.org/2000/xmlns/", "xmlns", "urn:org:ebics:H005")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:ds", "http://www.w3.org/2000/09/xmldsig#")
            attr("http://www.w3.org/2000/xmlns/", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attr("http://www.w3.org/2001/XMLSchema-instance", "xsi:schemaLocation", "urn:org:ebics:H005 ebics_request_H005.xsd")
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

    fun uploadInitialization(preparedUploadData: PreparedUploadData): ByteArray {
        val nonce = getNonce(128)
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("Nonce", nonce.toHexString())
                    el("Timestamp", Instant.now().xmlDateTime())
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    // SystemID
                    // Product
                    el("OrderDetails") {
                        el("AdminOrderType", "BTU")
                        el("BTUOrderParams") {
                            el("Service") {
                                el("ServiceName", "MCT")
                                el("Scope", "CH")
                                el("MsgName") {
                                    attr("version", "09")
                                    text("pain.001")
                                }
                            }
                            el("SignatureFlag", "true")
                        }
                    }
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

    fun downloadInitialization(whichDoc: SupportedDocument, startDate: Instant? = null, endDate: Instant? = null): ByteArray {
        val nonce = getNonce(128)
        return signedRequest {
            el("header") {
                attr("authenticate", "true")
                el("static") {
                    el("HostID", cfg.ebicsHostId)
                    el("Nonce", nonce.toHexString())
                    el("Timestamp", Instant.now().xmlDateTime())
                    el("PartnerID", cfg.ebicsPartnerId)
                    el("UserID", cfg.ebicsUserId)
                    // SystemID
                    // Product
                    el("OrderDetails") {
                        if (whichDoc == SupportedDocument.PAIN_002_LOGS) {
                            el("AdminOrderType", "HAC")
                        } else {
                            el("AdminOrderType", "BTD")
                            el("BTDOrderParams") {
                                el("Service") {
                                    el("ServiceName", when (whichDoc) {
                                        SupportedDocument.PAIN_002 -> "PSR"
                                        SupportedDocument.CAMT_052 -> "STM"
                                        SupportedDocument.CAMT_053 -> "EOP"
                                        SupportedDocument.CAMT_054 -> "REP"
                                        SupportedDocument.PAIN_002_LOGS -> throw Exception("Unreachable")
                                    })
                                    val (msg_value, msg_version) = when (whichDoc) {
                                        SupportedDocument.PAIN_002 -> Pair("pain.002", "10")
                                        SupportedDocument.CAMT_052 -> Pair("pain.052", "08")
                                        SupportedDocument.CAMT_053 -> Pair("pain.053", "08")
                                        SupportedDocument.CAMT_054 -> Pair("camt.054", "08")
                                        SupportedDocument.PAIN_002_LOGS -> throw Exception("Unreachable")
                                    }
                                    el("Scope", "CH")
                                    el("Container") {
                                        attr("containerType", "ZIP")
                                    }
                                    el("MsgName") {
                                        attr("version", msg_version)
                                        text(msg_value)
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
                el("mutable") {
                    el("TransactionPhase", "Initialisation")
                }
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
}

// TODO this function should not be here
/**
 * Collects all the steps to prepare the submission of a pain.001
 * document to the bank, and finally send it.  Indirectly throws
 * [EbicsSideException] or [EbicsUploadException].  The first means
 * that the bank sent an invalid response or signature, the second
 * that a proper EBICS or business error took place.  The caller must
 * catch those exceptions and decide the retry policy.
 *
 * @param pain001xml pain.001 document in XML.  The caller should
 *                   ensure its validity.
 * @param cfg configuration handle.
 * @param clientKeys client private keys.
 * @param bankkeys bank public keys.
 * @param httpClient HTTP client to connect to the bank.
 */
suspend fun submitPain001(
    pain001xml: String,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankkeys: BankPublicKeysFile,
    httpClient: HttpClient
): String {
    val orderService: Ebics3Request.OrderDetails.Service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "MCT"
        scope = "CH"
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "pain.001"
            version = "09"
        }
    }
    val maybeUploaded = doEbicsUpload(
        httpClient,
        cfg,
        clientKeys,
        bankkeys,
        orderService,
        pain001xml.toByteArray(Charsets.UTF_8),
    )
    logger.debug("Payment submitted, report text is: ${maybeUploaded.reportText}," +
            " EBICS technical code is: ${maybeUploaded.technicalReturnCode}," +
            " bank technical return code is: ${maybeUploaded.bankReturnCode}"
    )
    return maybeUploaded.orderID!!
}