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
import tech.libeufin.util.toHexString
import java.math.BigInteger
import java.util.*
import javax.xml.datatype.DatatypeFactory

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
    tech.libeufin.util.logger.debug("Created EBICS 3 document for upload initialization," +
            " nonce: ${nonce.toHexString()}")
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
        uploadData.encryptedPayloadChunks[chunkIndex]
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
 * document to the bank, and finally send it.
 *
 * @param pain001xml pain.001 document in XML.  The caller should
 *                   ensure its validity.
 * @param cfg configuration handle.
 * @param clientKeys client private keys.
 * @param bankkeys bank public keys.
 * @param httpClient HTTP client to connect to the bank.
 * @return true on success, false otherwise.
 */
suspend fun submitPayment(
    pain001xml: String,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankkeys: BankPublicKeysFile,
    httpClient: HttpClient
): Boolean {
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
    if (maybeUploaded == null) {
        logger.error("Could not send the pain.001 document to the bank.")
        return false
    }
    return true
}