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
 * This is the main "EBICS library interface".  Functions here are stateless helpers
 * used to implement both an EBICS server and EBICS client.
 */

package tech.libeufin.ebics

import io.ktor.http.*
import org.w3c.dom.Document
import tech.libeufin.common.crypto.CryptoUtil
import tech.libeufin.common.*
import java.io.InputStream
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar

data class EbicsProtocolError(
    val httpStatusCode: HttpStatusCode,
    val reason: String,
    /**
     * This class is also used when Nexus finds itself
     * in an inconsistent state, without interacting with the
     * bank.  In this case, the EBICS code below can be left
     * null.
     */
    val ebicsTechnicalCode: EbicsReturnCode? = null
) : Exception(reason)

/**
 * @param size in bits
 */
fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

data class PreparedUploadData(
    val transactionKey: ByteArray,
    val userSignatureDataEncrypted: ByteArray,
    val dataDigest: ByteArray,
    val encryptedPayloadChunks: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PreparedUploadData

        if (!transactionKey.contentEquals(other.transactionKey)) return false
        if (!userSignatureDataEncrypted.contentEquals(other.userSignatureDataEncrypted)) return false
        if (encryptedPayloadChunks != other.encryptedPayloadChunks) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionKey.contentHashCode()
        result = 31 * result + userSignatureDataEncrypted.contentHashCode()
        result = 31 * result + encryptedPayloadChunks.hashCode()
        return result
    }
}

data class DataEncryptionInfo(
    val transactionKey: ByteArray,
    val bankPubDigest: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataEncryptionInfo

        if (!transactionKey.contentEquals(other.transactionKey)) return false
        if (!bankPubDigest.contentEquals(other.bankPubDigest)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = transactionKey.contentHashCode()
        result = 31 * result + bankPubDigest.contentHashCode()
        return result
    }
}


// TODO import missing using a script
@Suppress("SpellCheckingInspection")
enum class EbicsReturnCode(val errorCode: String) {
    EBICS_OK("000000"),
    EBICS_DOWNLOAD_POSTPROCESS_DONE("011000"),
    EBICS_DOWNLOAD_POSTPROCESS_SKIPPED("011001"),
    EBICS_TX_SEGMENT_NUMBER_UNDERRUN("011101"),
    EBICS_AUTHENTICATION_FAILED("061001"),
    EBICS_INVALID_REQUEST("061002"),
    EBICS_INTERNAL_ERROR("061099"),
    EBICS_TX_RECOVERY_SYNC("061101"),
    EBICS_AUTHORISATION_ORDER_IDENTIFIER_FAILED("090003"),
    EBICS_INVALID_ORDER_DATA_FORMAT("090004"),
    EBICS_NO_DOWNLOAD_DATA_AVAILABLE("090005"),
    EBICS_INVALID_USER_OR_USER_STATE("091002"),
    EBICS_USER_UNKNOWN("091003"),
    EBICS_EBICS_INVALID_USER_STATE("091004"),
    EBICS_INVALID_ORDER_IDENTIFIER("091005"),
    EBICS_UNSUPPORTED_ORDER_TYPE("091006"),
    EBICS_INVALID_XML("091010"),
    EBICS_TX_MESSAGE_REPLAY("091103"),
    EBICS_PROCESSING_ERROR("091116"),
    EBICS_ACCOUNT_AUTHORISATION_FAILED("091302"),
    EBICS_AMOUNT_CHECK_FAILED("091303");

    companion object {
        fun lookup(errorCode: String): EbicsReturnCode {
            for (x in entries) {
                if (x.errorCode == errorCode) {
                    return x
                }
            }
            throw Exception(
                "Unknown EBICS status code: $errorCode"
            )
        }
    }
}


fun signOrderEbics3(
    orderBlob: ByteArray,
    signKey: RSAPrivateCrtKey,
    partnerId: String,
    userId: String
): ByteArray {
    return XmlBuilder.toString("UserSignatureData") {
        attr("xmlns", "http://www.ebics.org/S002")
        el("OrderSignatureData") {
            el("SignatureVersion", "A006")
            el("SignatureValue", CryptoUtil.signEbicsA006(
                CryptoUtil.digestEbicsOrderA006(orderBlob),
                signKey
            ).encodeBase64())
            el("PartnerID", partnerId)
            el("UserID", userId)
        }
    }.toByteArray()
}

data class EbicsResponseContent(
    val transactionID: String?,
    val orderID: String?,
    val dataEncryptionInfo: DataEncryptionInfo?,
    val orderDataEncChunk: String?,
    val technicalReturnCode: EbicsReturnCode,
    val bankReturnCode: EbicsReturnCode,
    val reportText: String,
    val segmentNumber: Int?,
    // Only present in init phase
    val numSegments: Int?
)

data class EbicsKeyManagementResponseContent(
    val technicalReturnCode: EbicsReturnCode,
    val bankReturnCode: EbicsReturnCode?,
    val orderData: ByteArray?
)


class HpbResponseData(
    val hostID: String,
    val encryptionPubKey: RSAPublicKey,
    val encryptionVersion: String,
    val authenticationPubKey: RSAPublicKey,
    val authenticationVersion: String
)


fun ebics3toInternalRepr(response: Document): EbicsResponseContent {
    // TODO better ebics response type
    return XmlDestructor.fromDoc(response, "ebicsResponse") {
        var transactionID: String? = null
        var numSegments: Int? = null
        lateinit var technicalReturnCode: EbicsReturnCode
        lateinit var bankReturnCode: EbicsReturnCode
        lateinit var reportText: String
        var orderID: String? = null
        var segmentNumber: Int? = null
        var orderDataEncChunk: String? = null
        var dataEncryptionInfo: DataEncryptionInfo? = null
        one("header") {
            one("static") {
                transactionID = opt("TransactionID")?.text()
                numSegments = opt("NumSegments")?.text()?.toInt()
            }
            one("mutable") {
                segmentNumber = opt("SegmentNumber")?.text()?.toInt()
                orderID = opt("OrderID")?.text()
                technicalReturnCode = EbicsReturnCode.lookup(one("ReturnCode").text())
                reportText = one("ReportText").text()
            }
        }
        one("body") {
            opt("DataTransfer") {
                orderDataEncChunk = one("OrderData").text()
                dataEncryptionInfo = opt("DataEncryptionInfo") {
                    DataEncryptionInfo(
                        one("TransactionKey").text().decodeBase64(),
                        one("EncryptionPubKeyDigest").text().decodeBase64()
                    )
                }
            }
            bankReturnCode = EbicsReturnCode.lookup(one("ReturnCode").text())
        }
        EbicsResponseContent(
            transactionID = transactionID,
            orderID = orderID,
            bankReturnCode = bankReturnCode,
            technicalReturnCode = technicalReturnCode,
            reportText = reportText,
            orderDataEncChunk = orderDataEncChunk,
            dataEncryptionInfo = dataEncryptionInfo,
            numSegments = numSegments,
            segmentNumber = segmentNumber
        )
    }
}

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