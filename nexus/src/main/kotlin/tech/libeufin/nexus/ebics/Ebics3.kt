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
import java.math.BigInteger
import java.util.*
import javax.xml.datatype.DatatypeFactory

/**
 * Crafts an EBICS request for the receipt phase of a
 * download transaction.
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
    orderParams: Ebics3Request.OrderDetails.BTOrderParams
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
 * [EbicsEarlyException] or [EbicsUploadException].  The first means
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
) {
    logger.debug("Submitting pain.001: $pain001xml")
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
        pain001xml.toByteArray(Charsets.UTF_8)
    )
    logger.debug("Payment submitted, report text is: ${maybeUploaded.reportText}," +
            " EBICS technical code is: ${maybeUploaded.technicalReturnCode}," +
            " bank technical return code is: ${maybeUploaded.bankReturnCode}"
    )
}