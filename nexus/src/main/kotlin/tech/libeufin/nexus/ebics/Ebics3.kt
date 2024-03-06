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
import tech.libeufin.ebics.PreparedUploadData
import tech.libeufin.ebics.XMLUtil
import tech.libeufin.ebics.ebics_h005.Ebics3Request
import tech.libeufin.ebics.getNonce
import tech.libeufin.ebics.getXmlDate
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.nexus.logger
import java.math.BigInteger
import java.time.Instant
import java.util.*
import javax.xml.datatype.DatatypeFactory

/**
 * Crafts an EBICS request for the receipt phase of a download
 * transaction.
 *
 * @param cfg config handle
 * @param clientKeys subscriber private keys.
 * @param transactionId EBICS transaction ID as assigned by the
 *        bank to any successful transaction.
 * @param success was the download successfully processed
 * @return the raw XML of the EBICS request.
 */
fun createEbics3DownloadReceiptPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    transactionId: String,
    success: Boolean
): ByteArray {
    val req = Ebics3Request.createForDownloadReceiptPhase(
        transactionId,
        cfg.ebicsHostId,
        success
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Crafts an EBICS download request for the transfer phase.
 *
 * @param cfg config handle
 * @param clientKeys subscriber private keys
 * @param transactionId EBICS transaction ID.  That came from the
 *        bank after the initialization phase ended successfully.
 * @param segmentNumber which (payload's) segment number this requests wants.
 * @param howManySegments total number of segments that the payload is split to.
 * @return the raw XML EBICS request.
 */
fun createEbics3DownloadTransferPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    howManySegments: Int,
    segmentNumber: Int,
    transactionId: String
): ByteArray {
    val req = Ebics3Request.createForDownloadTransferPhase(
        cfg.ebicsHostId,
        transactionId,
        segmentNumber,
        howManySegments
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToBytes(doc)
}


/**
 * Creates the EBICS 3 document for the init phase of a download
 * transaction.
 *
 * @param cfg configuration handle.
 * @param bankkeys bank public keys.
 * @param clientKeys client private keys.
 * @param orderService EBICS 3 document defining the request type
 */
fun createEbics3DownloadInitialization(
    cfg: EbicsSetupConfig,
    bankkeys: BankPublicKeysFile,
    clientKeys: ClientPrivateKeysFile,
    whichDoc: SupportedDocument,
    startDate: Instant? = null,
    endDate: Instant? = null
): ByteArray {
    val nonce = getNonce(128)
    val req = Ebics3Request.createForDownloadInitializationPhase(
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        cfg.ebicsHostId,
        nonce,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar()),
        bankAuthPub = bankkeys.bank_authentication_public_key,
        bankEncPub = bankkeys.bank_encryption_public_key,
        myOrderParams = if (whichDoc == SupportedDocument.PAIN_002_LOGS) null else Ebics3Request.OrderDetails.BTDOrderParams().apply {
            service = Ebics3Request.OrderDetails.Service().apply {
                serviceName = when(whichDoc) {
                    SupportedDocument.PAIN_002 -> "PSR"
                    SupportedDocument.CAMT_052 -> "STM"
                    SupportedDocument.CAMT_053 -> "EOP"
                    SupportedDocument.CAMT_054 -> "REP"
                    SupportedDocument.PAIN_002_LOGS -> "HAC"
                }
                scope = "CH"
                container = Ebics3Request.OrderDetails.Service.Container().apply {
                    containerType = "ZIP"
                }
                messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                    val (msg_value, msg_version) = when(whichDoc) {
                        SupportedDocument.PAIN_002 -> Pair("pain.002", "10")
                        SupportedDocument.CAMT_052 -> Pair("pain.052", "08")
                        SupportedDocument.CAMT_053 -> Pair("pain.053", "08")
                        SupportedDocument.CAMT_054 -> Pair("camt.054", "08")
                        SupportedDocument.PAIN_002_LOGS -> throw Exception("HAC (--only-logs) not available in EBICS 3")
                    }
                    value = msg_value
                    version = msg_version
                }
            }
            if (startDate != null) {
                Ebics3Request.DateRange().apply {
                    start = getXmlDate(startDate)
                    end = getXmlDate(endDate ?: Instant.now())
                }
            }
        }
    )
    val doc = XMLUtil.convertJaxbToDocument(
        req,
        withSchemaLocation = "urn:org:ebics:H005 ebics_request_H005.xsd"
    )
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Creates the EBICS 3 document for the init phase of an upload
 * transaction.
 *
 * @param cfg configuration handle.
 * @param preparedUploadData business payload to send.
 * @param bankkeys bank public keys.
 * @param clientKeys client private keys.
 * @param orderService EBICS 3 document defining the request type
 * @return raw XML of the EBICS 3 init phase.
 */
fun createEbics3RequestForUploadInitialization(
    cfg: EbicsSetupConfig,
    preparedUploadData: PreparedUploadData,
    bankkeys: BankPublicKeysFile,
    clientKeys: ClientPrivateKeysFile,
    orderService: Ebics3Request.OrderDetails.Service
): ByteArray {
    val nonce = getNonce(128)
    val req = Ebics3Request.createForUploadInitializationPhase(
        preparedUploadData.transactionKey,
        preparedUploadData.userSignatureDataEncrypted,
        preparedUploadData.dataDigest,
        cfg.ebicsHostId,
        nonce,
        cfg.ebicsPartnerId,
        cfg.ebicsUserId,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar()),
        bankkeys.bank_authentication_public_key,
        bankkeys.bank_encryption_public_key,
        BigInteger.ONE,
        orderService
    )
    val doc = XMLUtil.convertJaxbToDocument(
        req,
        withSchemaLocation = "urn:org:ebics:H005 ebics_request_H005.xsd"
    )
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToBytes(doc)
}

/**
 * Crafts one EBICS 3 request for the upload transfer phase.  Currently
 * only 1-chunk payloads are supported.
 *
 * @param cfg configuration handle.
 * @param clientKeys client private keys.
 * @param transactionId EBICS transaction ID obtained from an init phase.
 * @param uploadData business content to upload.
 *
 * @return raw XML document.
 */
fun createEbics3RequestForUploadTransferPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    transactionId: String,
    uploadData: PreparedUploadData
): ByteArray {
    val chunkIndex = 1 // only 1-chunk communication currently supported.
    val req = Ebics3Request.createForUploadTransferPhase(
        cfg.ebicsHostId,
        transactionId,
        BigInteger.valueOf(chunkIndex.toLong()),
        uploadData.encryptedPayloadChunks[chunkIndex - 1]
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToBytes(doc)
}

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