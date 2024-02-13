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

import io.ktor.client.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.ebics.*
import tech.libeufin.ebics.ebics_h004.*
import tech.libeufin.ebics.ebics_h005.Ebics3Request
import java.security.interfaces.RSAPrivateCrtKey
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.io.InputStream
import javax.xml.datatype.DatatypeFactory

private val logger: Logger = LoggerFactory.getLogger("libeufin-nexus-ebics2")

/**
 * Creates a EBICS 2.5 download init. message.  So far only used
 * to fetch the PostFinance bank accounts.
 */
fun createEbics25DownloadInit(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    orderType: String,
    orderParams: EbicsOrderParams = EbicsStandardOrderParams()
): ByteArray {
    val nonce = getNonce(128)
    val req = EbicsRequest.createForDownloadInitializationPhase(
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        cfg.ebicsHostId,
        nonce,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(
            GregorianCalendar(
            TimeZone.getTimeZone(ZoneId.systemDefault())
        )
        ),
        bankKeys.bank_encryption_public_key,
        bankKeys.bank_authentication_public_key,
        orderType,
        makeOrderParams(orderParams)
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Creates raw XML for an EBICS receipt phase.
 *
 * @param cfg configuration handle.
 * @param clientKeys user EBICS private keys.
 * @param transactionId transaction ID of the EBICS communication that
 *        should receive this receipt.
 * @param success was the download sucessfully processed
 * @return receipt request in XML.
 */
fun createEbics25DownloadReceiptPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    transactionId: String,
    success: Boolean
): ByteArray {
    val req = EbicsRequest.createForDownloadReceiptPhase(
        transactionId,
        cfg.ebicsHostId,
        success
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Creates raw XML for an EBICS transfer phase.
 *
 * @param cfg configuration handle.
 * @param clientKeys user EBICS private keys.
 * @param segNumber which segment we ask the bank.
 * @param totalSegments how many segments compose the whole EBICS transaction.
 * @param transactionId ID of the EBICS transaction that transports all the segments.
 * @return raw XML string of the request.
 */
fun createEbics25DownloadTransferPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    segNumber: Int,
    totalSegments: Int,
    transactionId: String
): ByteArray {
    val req = EbicsRequest.createForDownloadTransferPhase(
        hostID = cfg.ebicsHostId,
        segmentNumber = segNumber,
        numSegments = totalSegments,
        transactionID = transactionId
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = false
    )
    return XMLUtil.convertDomToBytes(doc)
}

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
    xml: InputStream
): EbicsKeyManagementResponseContent? {
    // TODO throw instead of null
    val jaxb = try {
        XMLUtil.convertToJaxb<EbicsKeyManagementResponse>(xml)
    } catch (e: Exception) {
        tech.libeufin.nexus.logger.error("Could not parse the raw response from bank into JAXB.")
        return null
    }
    var payload: ByteArray? = null
    jaxb.value.body.dataTransfer?.dataEncryptionInfo.apply {
        // non-null indicates that an encrypted payload should be found.
        if (this != null) {
            val encOrderData = jaxb.value.body.dataTransfer?.orderData?.value
            if (encOrderData == null) {
                tech.libeufin.nexus.logger.error("Despite a non-null DataEncryptionInfo, OrderData could not be found, can't decrypt any payload!")
                return null
            }
            payload = decryptAndDecompressPayload(
                clientEncryptionKey,
                DataEncryptionInfo(this.transactionKey, this.encryptionPubKeyDigest.value),
                listOf(encOrderData)
            ).readBytes()
        }
    }
    val bankReturnCode = EbicsReturnCode.lookup(jaxb.value.body.returnCode.value) // business error
    val ebicsReturnCode = EbicsReturnCode.lookup(jaxb.value.header.mutable.returnCode) // ebics error
    return EbicsKeyManagementResponseContent(ebicsReturnCode, bankReturnCode, payload)
}

/**
 * Generates the INI message to upload the signature key.
 *
 * @param cfg handle to the configuration.
 * @param clientKeys set of all the client keys.
 * @return the raw EBICS INI message.
 */
fun generateIniMessage(cfg: EbicsSetupConfig, clientKeys: ClientPrivateKeysFile): ByteArray {
    val iniRequest = EbicsUnsecuredRequest.createIni(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.signature_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(iniRequest)
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
    val hiaRequest = EbicsUnsecuredRequest.createHia(
        cfg.ebicsHostId,
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        clientKeys.authentication_private_key,
        clientKeys.encryption_private_key
    )
    val doc = XMLUtil.convertJaxbToDocument(hiaRequest)
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
    val hpbRequest = EbicsNpkdRequest.createRequest(
        cfg.ebicsHostId,
        cfg.ebicsPartnerId,
        cfg.ebicsUserId,
        getNonce(128),
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar())
    )
    val doc = XMLUtil.convertJaxbToDocument(hpbRequest)
    XMLUtil.signEbicsDocument(doc, clientKeys.authentication_private_key)
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Collects message type and date range of an EBICS 2 request.
 */
data class Ebics2Request(
    val messageType: String,
    val orderParams: EbicsOrderParams
)

/**
 * Prepares an EBICS 2 request to get pain.002 acknowledgements
 * about submitted pain.001 documents.
 *
 * @param startDate earliest timestamp of the returned document(s).  If
 *        null, it defaults to download the unseen documents.
 * @param endDate latest timestamp of the returned document(s).  If
 *        null, it defaults to the current time.
 * @return [Ebics2Request] object to be first converted in XML and
 *         then be passed to the EBICS downloader.
 */
private fun prepAckRequest2(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics2Request {
    val maybeDateRange = if (startDate != null) EbicsDateRange(startDate, endDate ?: Instant.now()) else null
    return Ebics2Request(
        messageType = "Z01",
        orderParams = EbicsStandardOrderParams(dateRange = maybeDateRange)
    )
}

/**
 * Prepares an EBICS 2 request to get intraday camt.052 reports.
 *
 * @param startDate earliest timestamp of the returned document(s).  If
 *        null, it defaults to download the unseen documents.
 * @param endDate latest timestamp of the returned document(s).  If
 *        null, it defaults to the current time.
 * @return [Ebics2Request] object to be first converted in XML and
 *         then be passed to the EBICS downloader.
 */
private fun prepReportRequest2(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics2Request {
    val maybeDateRange = if (startDate != null) EbicsDateRange(startDate, endDate ?: Instant.now()) else null
    return Ebics2Request(
        messageType = "Z52",
        orderParams = EbicsStandardOrderParams(dateRange = maybeDateRange)
    )
}

/**
 * Prepares an EBICS 2 request to get daily camt.053 statements.
 *
 * @param startDate earliest timestamp of the returned document(s).  If
 *        null, it defaults to download the unseen documents.
 * @param endDate latest timestamp of the returned document(s).  If
 *        null, it defaults to the current time.
 * @return [Ebics2Request] object to be first converted in XML and
 *         then be passed to the EBICS downloader.
 */
private fun prepStatementRequest2(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics2Request {
    val maybeDateRange = if (startDate != null) EbicsDateRange(startDate, endDate ?: Instant.now()) else null
    return Ebics2Request(
        messageType = "Z53",
        orderParams = EbicsStandardOrderParams(dateRange = maybeDateRange)
    )
}

/**
 * Prepares an EBICS 2 request to get camt.054 notifications.
 *
 * @param startDate earliest timestamp of the returned document(s).  If
 *        null, it defaults to download the unseen documents.
 * @param endDate latest timestamp of the returned document(s).  If
 *        null, it defaults to the current time.
 * @return [Ebics2Request] object to be first converted in XML and
 *         then be passed to the EBICS downloader.
 */
private fun prepNotificationRequest2(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics2Request {
    val maybeDateRange = if (startDate != null) EbicsDateRange(startDate, endDate ?: Instant.now()) else null
    return Ebics2Request(
        messageType = "Z54", // ZS2 is the non-appendix type
        orderParams = EbicsStandardOrderParams(dateRange = maybeDateRange)
    )
}

/**
 * Prepares an EBICS 2 request to get logs from the bank about any
 * uploaded or downloaded document.
 *
 * @param startDate earliest timestamp of the returned document(s).  If
 *        null, it defaults to download the unseen documents.
 * @param endDate latest timestamp of the returned document(s).  If
 *        null, it defaults to the current time.
 * @return [Ebics2Request] object to be first converted in XML and
 *         then be passed to the EBICS downloader.
 */
private fun prepLogsRequest2(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics2Request {
    val maybeDateRange = if (startDate != null) EbicsDateRange(startDate, endDate ?: Instant.now()) else null
    return Ebics2Request(
        messageType = "HAC",
        orderParams = EbicsStandardOrderParams(dateRange = maybeDateRange)
    )
}

/**
 * Abstracts EBICS 2 request creation of a download init phase.
 *
 * @param whichDoc type of wanted document.
 * @param startDate earliest timestamp of the document(s) to download.
 *                  If null, it gets the unseen documents.  If defined,
 *                  the latest timestamp defaults to the current time.
 * @return [Ebics2Request] to be converted to XML string and passed to
 *         the EBICS downloader.
 */
fun prepEbics2Document(
    whichDoc: SupportedDocument,
    startDate: Instant? = null
): Ebics2Request =
    when(whichDoc) {
        SupportedDocument.PAIN_002 -> prepAckRequest2(startDate)
        SupportedDocument.CAMT_052 -> prepReportRequest2(startDate)
        SupportedDocument.CAMT_053 -> prepStatementRequest2(startDate)
        SupportedDocument.CAMT_054 -> prepNotificationRequest2(startDate)
        SupportedDocument.PAIN_002_LOGS -> prepLogsRequest2(startDate)
    }