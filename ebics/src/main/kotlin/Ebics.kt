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

import tech.libeufin.common.CryptoUtil
import io.ktor.http.HttpStatusCode
import tech.libeufin.ebics.ebics_h004.*
import tech.libeufin.ebics.ebics_h005.Ebics3Response
import tech.libeufin.ebics.ebics_s001.UserSignatureData
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import javax.xml.bind.JAXBElement
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

data class EbicsDateRange(
    val start: Instant,
    val end: Instant
)

sealed interface EbicsOrderParams
data class EbicsStandardOrderParams(
    val dateRange: EbicsDateRange? = null
) : EbicsOrderParams

data class EbicsGenericOrderParams(
    val params: Map<String, String> = mapOf()
) : EbicsOrderParams

enum class EbicsInitState {
    SENT, NOT_SENT, UNKNOWN
}

/**
 * This class is a mere container that keeps data found
 * in the database and that is further needed to sign / verify
 * / make messages.  And not all the values are needed all
 * the time.
 */
data class EbicsClientSubscriberDetails(
    val partnerId: String,
    val userId: String,
    var bankAuthPub: RSAPublicKey?,
    var bankEncPub: RSAPublicKey?,
    val ebicsUrl: String,
    val hostId: String,
    val customerEncPriv: RSAPrivateCrtKey,
    val customerAuthPriv: RSAPrivateCrtKey,
    val customerSignPriv: RSAPrivateCrtKey,
    val ebicsIniState: EbicsInitState,
    val ebicsHiaState: EbicsInitState,
    var dialect: String? = null
)

/**
 * @param size in bits
 */
fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

fun getXmlDate(i: Instant): XMLGregorianCalendar {
    val zonedTimestamp = ZonedDateTime.ofInstant(i, ZoneId.of("UTC"))
    return getXmlDate(zonedTimestamp)
}
fun getXmlDate(d: ZonedDateTime): XMLGregorianCalendar {
    return DatatypeFactory.newInstance()
        .newXMLGregorianCalendar(
            d.year,
            d.monthValue,
            d.dayOfMonth,
            0,
            0,
            0,
            0,
            d.offset.totalSeconds / 60
        )
}

fun makeOrderParams(orderParams: EbicsOrderParams): EbicsRequest.OrderParams {
    return when (orderParams) {
        is EbicsStandardOrderParams -> {
            EbicsRequest.StandardOrderParams().apply {
                val r = orderParams.dateRange
                if (r != null) {
                    this.dateRange = EbicsRequest.DateRange().apply {
                        this.start = getXmlDate(r.start)
                        this.end = getXmlDate(r.end)
                    }
                }
            }
        }
        is EbicsGenericOrderParams -> {
            EbicsRequest.GenericOrderParams().apply {
                this.parameterList = orderParams.params.map { entry ->
                    EbicsTypes.Parameter().apply {
                        this.name = entry.key
                        this.value = entry.value
                        this.type = "string"
                    }
                }
            }
        }
    }
}

fun signOrder(
    orderBlob: ByteArray,
    signKey: RSAPrivateCrtKey,
    partnerId: String,
    userId: String
): UserSignatureData {
    val ES_signature = CryptoUtil.signEbicsA006(
        CryptoUtil.digestEbicsOrderA006(orderBlob),
        signKey
    )
    val userSignatureData = UserSignatureData().apply {
        orderSignatureList = listOf(
            UserSignatureData.OrderSignatureData().apply {
                signatureVersion = "A006"
                signatureValue = ES_signature
                partnerID = partnerId
                userID = userId
            }
        )
    }
    return userSignatureData
}

fun signOrderEbics3(
    orderBlob: ByteArray,
    signKey: RSAPrivateCrtKey,
    partnerId: String,
    userId: String
): tech.libeufin.ebics.ebics_s002.UserSignatureDataEbics3 {
    val ES_signature = CryptoUtil.signEbicsA006(
        CryptoUtil.digestEbicsOrderA006(orderBlob),
        signKey
    )
    val userSignatureData = tech.libeufin.ebics.ebics_s002.UserSignatureDataEbics3().apply {
        orderSignatureList = listOf(
            tech.libeufin.ebics.ebics_s002.UserSignatureDataEbics3.OrderSignatureData().apply {
                signatureVersion = "A006"
                signatureValue = ES_signature
                partnerID = partnerId
                userID = userId
            }
        )
    }
    return userSignatureData
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
            for (x in values()) {
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

data class EbicsResponseContent(
    val transactionID: String?,
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

fun parseEbicsHpbOrder(orderDataRaw: ByteArray): HpbResponseData {
    val resp = try {
        XMLUtil.convertStringToJaxb<HPBResponseOrderData>(orderDataRaw.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.InternalServerError, "Invalid XML (as HPB response) received from bank")
    }
    val encPubKey = CryptoUtil.loadRsaPublicKeyFromComponents(
        resp.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
        resp.value.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
    )
    val authPubKey = CryptoUtil.loadRsaPublicKeyFromComponents(
        resp.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.modulus,
        resp.value.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue.exponent
    )
    return HpbResponseData(
        hostID = resp.value.hostID,
        encryptionPubKey = encPubKey,
        encryptionVersion = resp.value.encryptionPubKeyInfo.encryptionVersion,
        authenticationPubKey = authPubKey,
        authenticationVersion = resp.value.authenticationPubKeyInfo.authenticationVersion
    )
}

fun ebics3toInternalRepr(response: String): EbicsResponseContent {
    // logger.debug("Converting bank resp to internal repr.: $response")
    val resp: JAXBElement<Ebics3Response> = try {
        XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw EbicsProtocolError(
            HttpStatusCode.InternalServerError,
            "Could not transform string-response from bank into JAXB"
        )
    }
    val bankReturnCodeStr = resp.value.body.returnCode.value
    val bankReturnCode = EbicsReturnCode.lookup(bankReturnCodeStr)

    val techReturnCodeStr = resp.value.header.mutable.returnCode
    val techReturnCode = EbicsReturnCode.lookup(techReturnCodeStr)

    val reportText = resp.value.header.mutable.reportText

    val daeXml = resp.value.body.dataTransfer?.dataEncryptionInfo
    val dataEncryptionInfo = if (daeXml == null) {
        null
    } else {
        DataEncryptionInfo(daeXml.transactionKey, daeXml.encryptionPubKeyDigest.value)
    }

    return EbicsResponseContent(
        transactionID = resp.value.header._static.transactionID,
        bankReturnCode = bankReturnCode,
        technicalReturnCode = techReturnCode,
        reportText = reportText,
        orderDataEncChunk = resp.value.body.dataTransfer?.orderData?.value,
        dataEncryptionInfo = dataEncryptionInfo,
        numSegments = resp.value.header._static.numSegments?.toInt(),
        segmentNumber = resp.value.header.mutable.segmentNumber?.value?.toInt()
    )
}

fun ebics25toInternalRepr(response: String): EbicsResponseContent {
    val resp: JAXBElement<EbicsResponse> = try {
        XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw EbicsProtocolError(
            HttpStatusCode.InternalServerError,
            "Could not transform string-response from bank into JAXB"
        )
    }
    val bankReturnCodeStr = resp.value.body.returnCode.value
    val bankReturnCode = EbicsReturnCode.lookup(bankReturnCodeStr)

    val techReturnCodeStr = resp.value.header.mutable.returnCode
    val techReturnCode = EbicsReturnCode.lookup(techReturnCodeStr)

    val reportText = resp.value.header.mutable.reportText

    val daeXml = resp.value.body.dataTransfer?.dataEncryptionInfo
    val dataEncryptionInfo = if (daeXml == null) {
        null
    } else {
        DataEncryptionInfo(daeXml.transactionKey, daeXml.encryptionPubKeyDigest.value)
    }

    return EbicsResponseContent(
        transactionID = resp.value.header._static.transactionID,
        bankReturnCode = bankReturnCode,
        technicalReturnCode = techReturnCode,
        reportText = reportText,
        orderDataEncChunk = resp.value.body.dataTransfer?.orderData?.value,
        dataEncryptionInfo = dataEncryptionInfo,
        numSegments = resp.value.header._static.numSegments?.toInt(),
        segmentNumber = resp.value.header.mutable.segmentNumber?.value?.toInt()
    )
}

/**
 * Get the private key that matches the given public key digest.
 */
fun getDecryptionKey(subscriberDetails: EbicsClientSubscriberDetails, pubDigest: ByteArray): RSAPrivateCrtKey {
    val authPub = CryptoUtil.getRsaPublicFromPrivate(subscriberDetails.customerAuthPriv)
    val encPub = CryptoUtil.getRsaPublicFromPrivate(subscriberDetails.customerEncPriv)
    val authPubDigest = CryptoUtil.getEbicsPublicKeyHash(authPub)
    val encPubDigest = CryptoUtil.getEbicsPublicKeyHash(encPub)
    if (pubDigest.contentEquals(authPubDigest)) {
        return subscriberDetails.customerAuthPriv
    }
    if (pubDigest.contentEquals(encPubDigest)) {
        return subscriberDetails.customerEncPriv
    }
    throw EbicsProtocolError(HttpStatusCode.NotFound, "Could not find customer's public key")
}

data class EbicsVersionSpec(
    val protocol: String,
    val version: String
)

data class EbicsHevDetails(
    val versions: List<EbicsVersionSpec>
)