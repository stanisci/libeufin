package tech.libeufin.nexus.ebics

import io.ktor.client.*
import tech.libeufin.nexus.BankPublicKeysFile
import tech.libeufin.nexus.ClientPrivateKeysFile
import tech.libeufin.nexus.EbicsSetupConfig
import tech.libeufin.nexus.logger
import tech.libeufin.util.PreparedUploadData
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.getNonce
import tech.libeufin.util.getXmlDate
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
 * @return the raw XML of the EBICS request.
 */
fun createEbics3DownloadReceiptPhase(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    transactionId: String
): String {
    val req = Ebics3Request.createForDownloadReceiptPhase(
        transactionId,
        cfg.ebicsHostId
    )
    val doc = XMLUtil.convertJaxbToDocument(req)
    XMLUtil.signEbicsDocument(
        doc,
        clientKeys.authentication_private_key,
        withEbics3 = true
    )
    return XMLUtil.convertDomToString(doc)
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
): String {
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
    return XMLUtil.convertDomToString(doc)
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
    orderParams: Ebics3Request.OrderDetails.BTDOrderParams
): String {
    val nonce = getNonce(128)
    val req = Ebics3Request.createForDownloadInitializationPhase(
        cfg.ebicsUserId,
        cfg.ebicsPartnerId,
        cfg.ebicsHostId,
        nonce,
        DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar()),
        bankAuthPub = bankkeys.bank_authentication_public_key,
        bankEncPub = bankkeys.bank_encryption_public_key,
        myOrderParams = orderParams
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
    return XMLUtil.convertDomToString(doc)
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
): String {
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
    return XMLUtil.convertDomToString(doc)
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
): String {
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
    return XMLUtil.convertDomToString(doc)
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
    httpClient: HttpClient,
    ebicsExtraLog: Boolean = false
) {
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
        ebicsExtraLog
    )
    logger.debug("Payment submitted, report text is: ${maybeUploaded.reportText}," +
            " EBICS technical code is: ${maybeUploaded.technicalReturnCode}," +
            " bank technical return code is: ${maybeUploaded.bankReturnCode}"
    )
}

/**
 * Crafts a date range object, when the caller needs a time range.
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.
 * @return [Ebics3Request.DateRange]
 */
private fun getEbics3DateRange(
    startDate: Instant,
    endDate: Instant
): Ebics3Request.DateRange {
    return Ebics3Request.DateRange().apply {
        start = getXmlDate(startDate)
        end = getXmlDate(endDate)
    }
}

/**
 * Prepares the request for a camt.054 notification from the bank,
 * via EBICS 3.
 * Notifications inform the subscriber that some new events occurred
 * on their account.  One main difference with reports/statements is
 * that notifications - according to the ISO20022 documentation - do
 * NOT contain any balance.
 *
 * @param startDate inclusive starting date for the returned notification(s).
 * @param endDate inclusive ending date for the returned notification(s).  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 * @param isAppendix if true, the responded camt.054 will be an appendix of
 *        another camt.053 document, not therefore strictly acting as a notification.
 *        For example, camt.053 may omit wire transfer subjects and its related
 *        camt.054 appendix would instead contain those.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepNotificationRequest3(
    startDate: Instant? = null,
    endDate: Instant? = null,
    isAppendix: Boolean
): Ebics3Request.OrderDetails.BTDOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "REP"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.054"
            version = "08"
        }
        if (!isAppendix)
            serviceOption = "XDCI"
    }
    return Ebics3Request.OrderDetails.BTDOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for a pain.002 acknowledgement from the bank, via
 * EBICS 3.
 *
 * @param startDate inclusive starting date for the returned acknowledgements.
 * @param endDate inclusive ending date for the returned acknowledgements.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepAckRequest3(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTDOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "PSR"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "pain.002"
            version = "10"
        }
    }
    return Ebics3Request.OrderDetails.BTDOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for (a) camt.053/statement(s) via EBICS 3.
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepStatementRequest3(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTDOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "EOP"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.053"
            version = "08"
        }
    }
    return Ebics3Request.OrderDetails.BTDOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for camt.052/intraday records via EBICS 3.
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepReportRequest3(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTDOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "STM"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.052"
            version = "08"
        }
    }
    return Ebics3Request.OrderDetails.BTDOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Abstracts EBICS 3 request creation of a download init phase.
 *
 * @param whichDoc type of wanted document.
 * @param startDate earliest timestamp of the document(s) to download.
 *                  If null, it gets the unseen documents.  If defined,
 *                  the latest timestamp defaults to the current time.
 * @return [Ebics2Request] to be converted to XML string and passed to
 *         the EBICS downloader.
 */
fun prepEbics3Document(
    whichDoc: SupportedDocument,
    startDate: Instant? = null
): Ebics3Request.OrderDetails.BTDOrderParams =
    when(whichDoc) {
        SupportedDocument.PAIN_002 -> prepAckRequest3(startDate)
        SupportedDocument.CAMT_052 -> prepReportRequest3(startDate)
        SupportedDocument.CAMT_053 -> prepStatementRequest3(startDate)
        SupportedDocument.CAMT_054 -> prepNotificationRequest3(startDate, isAppendix = true)
        SupportedDocument.PAIN_002_LOGS -> throw Exception("HAC (--only-logs) not available in EBICS 3")
    }