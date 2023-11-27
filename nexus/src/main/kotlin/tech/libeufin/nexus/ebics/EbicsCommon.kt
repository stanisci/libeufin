/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
 * This file collects the EBICS helpers in the most version-independent way.
 * It tries therefore to make the helpers reusable across the EBICS versions 2.x
 * and 3.x.
 */

/**
 * NOTE: it has been observed that even with a EBICS 3 server, it
 * is still possible to exchange the keys via the EBICS 2.5 protocol.
 * That is how this file does, but future versions should implement the
 * EBICS 3 keying.
 */

package tech.libeufin.nexus.ebics

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.DeflaterInputStream

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus.EbicsCommon")

/**
 * Available EBICS versions.
 */
enum class EbicsVersion { two, three }

/**
 * Which documents can be downloaded via EBICS.
 */
enum class SupportedDocument {
    /**
     * Payment acknowledgement.
     */
    PAIN_002,
    /**
     * From an HAC request.  Informs about any
     * download/upload activity, including wrong
     * documents.
     */
    PAIN_002_LOGS,
    /**
     * Account statements.
     */
    CAMT_053,
    /**
     * Account intraday reports.
     */
    CAMT_052,
    /**
     * Account notifications.
     */
    CAMT_054
}


/**
 * Unzips the ByteArray and runs the lambda over each entry.
 *
 * @param lambda function that gets the (fileName, fileContent) pair
 *        for each entry in the ZIP archive as input.
 */
fun ByteArray.unzipForEach(lambda: (String, String) -> Unit) {
    if (this.isEmpty()) {
        tech.libeufin.nexus.logger.warn("Empty archive")
        return
    }
    val mem = SeekableInMemoryByteChannel(this)
    val zipFile = ZipFile(mem)
    zipFile.getEntriesInPhysicalOrder().iterator().forEach {
        lambda(
            it.name, zipFile.getInputStream(it).readAllBytes().toString(Charsets.UTF_8)
        )
    }
    zipFile.close()
}

/**
 * Decrypts and decompresses the business payload that was
 * transported within an EBICS message from the bank
 *
 * @param clientEncryptionKey client private encryption key, used to decrypt
 *                            the transaction key.  The transaction key is the
 *                            one actually used to encrypt the payload.
 * @param encryptionInfo details related to the encrypted payload.
 * @param chunks the several chunks that constitute the whole encrypted payload.
 * @return the plain payload.  Errors throw, so the caller must handle those.
 */
fun decryptAndDecompressPayload(
    clientEncryptionKey: RSAPrivateCrtKey,
    encryptionInfo: DataEncryptionInfo,
    chunks: List<String>
): ByteArray {
    val buf = StringBuilder()
    chunks.forEach { buf.append(it) }
    val decoded = Base64.getDecoder().decode(buf.toString())
    val er = CryptoUtil.EncryptionResult(
        encryptionInfo.transactionKey,
        encryptionInfo.bankPubDigest,
        decoded
    )
    val dataCompr = CryptoUtil.decryptEbicsE002(
        er,
        clientEncryptionKey
    )
    return EbicsOrderUtil.decodeOrderData(dataCompr)
}

/**
 * POSTs the EBICS message to the bank.
 *
 * @param URL where the bank serves EBICS requests.
 * @param msg EBICS message as raw string.
 * @return the raw bank response, if the request made it to the
 *         EBICS handler, or null otherwise.
 */
suspend fun HttpClient.postToBank(bankUrl: String, msg: String): String? {
    logger.debug("POSTing EBICS to: $bankUrl")
    val resp: HttpResponse = try {
        this.post(urlString = bankUrl) {
            expectSuccess = false // avoids exceptions on non-2xx statuses.
            contentType(ContentType.Text.Xml)
            setBody(msg)
        }
    }
    catch (e: Exception) {
        // hard error (network issue, invalid URL, ..)
        tech.libeufin.nexus.logger.error("Could not POST to bank at: $bankUrl, detail: ${e.message}")
        return null
    }
    // Bank was found, but the EBICS request wasn't served.
    // Note: EBICS errors get _still_ 200 OK, so here the error
    // _should_ not be related to EBICS.  404 for a wrong URL
    // is one example.
    if (resp.status != HttpStatusCode.OK) {
        tech.libeufin.nexus.logger.error("Bank was found at $bankUrl, but EBICS wasn't served.  Response status: ${resp.status}, body: ${resp.bodyAsText()}")
        return null
    }
    return resp.bodyAsText()
}

/**
 * Generate the PDF document with all the client public keys
 * to be sent on paper to the bank.
 */
fun generateKeysPdf(
    clientKeys: ClientPrivateKeysFile,
    cfg: EbicsSetupConfig
): ByteArray {
    val po = ByteArrayOutputStream()
    val pdfWriter = PdfWriter(po)
    val pdfDoc = PdfDocument(pdfWriter)
    val date = LocalDateTime.now()
    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun formatHex(ba: ByteArray): String {
        var out = ""
        for (i in ba.indices) {
            val b = ba[i]
            if (i > 0 && i % 16 == 0) {
                out += "\n"
            }
            out += java.lang.String.format("%02X", b)
            out += " "
        }
        return out
    }

    fun writeCommon(doc: Document) {
        doc.add(
            Paragraph(
                """
            Datum: $dateStr
            Host-ID: ${cfg.ebicsHostId}
            User-ID: ${cfg.ebicsUserId}
            Partner-ID: ${cfg.ebicsPartnerId}
            ES version: A006
        """.trimIndent()
            )
        )
    }

    fun writeKey(doc: Document, priv: RSAPrivateCrtKey) {
        val pub = CryptoUtil.getRsaPublicFromPrivate(priv)
        val hash = CryptoUtil.getEbicsPublicKeyHash(pub)
        doc.add(Paragraph("Exponent:\n${formatHex(pub.publicExponent.toByteArray())}"))
        doc.add(Paragraph("Modulus:\n${formatHex(pub.modulus.toByteArray())}"))
        doc.add(Paragraph("SHA-256 hash:\n${formatHex(hash)}"))
    }

    fun writeSigLine(doc: Document) {
        doc.add(Paragraph("Ort / Datum: ________________"))
        doc.add(Paragraph("Firma / Name: ________________"))
        doc.add(Paragraph("Unterschrift: ________________"))
    }

    Document(pdfDoc).use {
        it.add(Paragraph("Signaturschlüssel").setFontSize(24f))
        writeCommon(it)
        it.add(Paragraph("Öffentlicher Schlüssel (Public key for the electronic signature)"))
        writeKey(it, clientKeys.signature_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
        it.add(AreaBreak())

        it.add(Paragraph("Authentifikationsschlüssel").setFontSize(24f))
        writeCommon(it)
        it.add(Paragraph("Öffentlicher Schlüssel (Public key for the identification and authentication signature)"))
        writeKey(it, clientKeys.authentication_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
        it.add(AreaBreak())

        it.add(Paragraph("Verschlüsselungsschlüssel").setFontSize(24f))
        writeCommon(it)
        it.add(Paragraph("Öffentlicher Schlüssel (Public encryption key)"))
        writeKey(it, clientKeys.encryption_private_key)
        it.add(Paragraph("\n"))
        writeSigLine(it)
    }
    pdfWriter.flush()
    return po.toByteArray()
}

/**
 * POSTs raw EBICS XML to the bank and checks the two return codes:
 * EBICS- and bank-technical.
 *
 * @param clientKeys client keys, used to sign the request.
 * @param bankKeys bank keys, used to decrypt and validate the response.
 * @param xmlBody raw EBICS request in XML.
 * @param withEbics3 true in case the communication is EBICS 3, false otherwise.
 * @param tolerateEbicsReturnCode EBICS technical return code that may be accepted
 *                                instead of EBICS_OK.  That is the case of EBICS_DOWNLOAD_POSTPROCESS_DONE
 *                                along download receipt phases.
 * @param tolerateBankReturnCode Business return code that may be accepted instead of
 *                               EBICS_OK.  Typically, EBICS_NO_DOWNLOAD_DATA_AVAILABLE is tolerated
 *                               when asking for new incoming payments.
 * @return [EbicsResponseContent] or throws [EbicsSideException]
 */
suspend fun postEbics(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    bankKeys: BankPublicKeysFile,
    xmlReq: String,
    isEbics3: Boolean
): EbicsResponseContent {
    val respXml = client.postToBank(cfg.hostBaseUrl, xmlReq)
        ?: throw EbicsSideException(
            "POSTing to ${cfg.hostBaseUrl} failed",
            sideEc = EbicsSideError.HTTP_POST_FAILED
        )
    return parseAndValidateEbicsResponse(
        bankKeys,
        respXml,
        isEbics3
    )
}

/**
 * Checks that EBICS- and bank-technical return codes are both EBICS_OK.
 *
 * @param ebicsResponseContent valid response gotten from the bank.
 * @return true only if both codes are EBICS_OK.
 */
private fun areCodesOk(ebicsResponseContent: EbicsResponseContent) =
    ebicsResponseContent.technicalReturnCode == EbicsReturnCode.EBICS_OK &&
            ebicsResponseContent.bankReturnCode == EbicsReturnCode.EBICS_OK

/**
 * Collects all the steps of an EBICS download transaction.  Namely,
 * it conducts: init -> transfer -> receipt phases.
 *
 * @param client HTTP client for POSTing to the bank.
 * @param cfg configuration handle.
 * @param clientKeys client EBICS private keys.
 * @param bankKeys bank EBICS public keys.
 * @param reqXml raw EBICS XML request of the init phase.
 * @param isEbics3 true for EBICS 3, false otherwise.
 * @param tolerateEmptyResult true if the EC EBICS_NO_DOWNLOAD_DATA_AVAILABLE
 *        should be tolerated as the bank-technical error, false otherwise.
 * @return the bank response as an uncompressed [ByteArray], or null if one
 *         error took place.  Definition of error: any EBICS- or bank-technical
 *         EC pairs where at least one is not EBICS_OK, or if tolerateEmptyResult
 *         is true, the bank-technical EC EBICS_NO_DOWNLOAD_DATA_AVAILABLE is allowed
 *         other than EBICS_OK.  If the request tolerates an empty download content,
 *         then the empty array is returned.  The function may throw [EbicsAdditionalErrors].
 */
suspend fun doEbicsDownload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    reqXml: String,
    isEbics3: Boolean,
    tolerateEmptyResult: Boolean = false
): ByteArray? {
    val initResp = postEbics(client, cfg, bankKeys, reqXml, isEbics3)
    logger.debug("Download init phase done.  EBICS- and bank-technical codes are: ${initResp.technicalReturnCode}, ${initResp.bankReturnCode}")
    if (initResp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        logger.error("Download init phase has EBICS-technical error: ${initResp.technicalReturnCode}")
        return null
    }
    if (initResp.bankReturnCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE && tolerateEmptyResult) {
        logger.info("Download content is empty")
        return ByteArray(0)
    }
    if (initResp.bankReturnCode != EbicsReturnCode.EBICS_OK) {
        logger.error("Download init phase has bank-technical error: ${initResp.bankReturnCode}")
        return null
    }
    val tId = initResp.transactionID
        ?: throw EbicsSideException(
            "EBICS download init phase did not return a transaction ID, cannot do the transfer phase.",
            sideEc = EbicsSideError.EBICS_UPLOAD_TRANSACTION_ID_MISSING
        )
    logger.debug("EBICS download transaction passed the init phase, got ID: $tId")
    val howManySegments = initResp.numSegments
    if (howManySegments == null) {
        tech.libeufin.nexus.logger.error("Init response lacks the quantity of segments, failing.")
        return null
    }
    val ebicsChunks = mutableListOf<String>()
    // Getting the chunk(s)
    val firstDataChunk = initResp.orderDataEncChunk
        ?: throw EbicsSideException(
            "OrderData element not found, despite non empty payload, failing.",
            sideEc = EbicsSideError.ORDER_DATA_ELEMENT_NOT_FOUND
        )
    val dataEncryptionInfo = initResp.dataEncryptionInfo ?: run {
        throw EbicsSideException(
            "EncryptionInfo element not found, despite non empty payload, failing.",
            sideEc = EbicsSideError.ENCRYPTION_INFO_ELEMENT_NOT_FOUND
        )
    }
    ebicsChunks.add(firstDataChunk)
    // proceed with the transfer phase.
    for (x in 2 .. howManySegments) {
        // request segment number x.
        val transReq = if (isEbics3)
            createEbics3DownloadTransferPhase(cfg, clientKeys, x, howManySegments, tId)
        else createEbics25DownloadTransferPhase(cfg, clientKeys, x, howManySegments, tId)

        val transResp = postEbics(client, cfg, bankKeys, transReq, isEbics3)
        if (!areCodesOk(transResp)) {
            throw EbicsSideException(
                "EBICS transfer segment #$x failed.",
                sideEc = EbicsSideError.TRANSFER_SEGMENT_FAILED
            )
        }
        val chunk = transResp.orderDataEncChunk
        if (chunk == null) {
            tech.libeufin.nexus.logger.error("EBICS transfer phase lacks chunk #$x, failing.")
            return null
        }
        ebicsChunks.add(chunk)
    }
    // all chunks gotten, shaping a meaningful response now.
    val payloadBytes = decryptAndDecompressPayload(
        clientKeys.encryption_private_key,
        dataEncryptionInfo,
        ebicsChunks
    )
    // payload reconstructed, receipt to the bank.
    val receiptXml = if (isEbics3)
        createEbics3DownloadReceiptPhase(cfg, clientKeys, tId)
    else createEbics25DownloadReceiptPhase(cfg, clientKeys, tId)

    // Sending the receipt to the bank.
    postEbics(
        client,
        cfg,
        bankKeys,
        receiptXml,
        isEbics3
    )
    // Receipt didn't throw, can now return the payload.
    return payloadBytes
}

/**
 * These errors affect an EBICS transaction regardless
 * of the standard error codes.
 */
enum class EbicsSideError {
    BANK_SIGNATURE_DIDNT_VERIFY,
    BANK_RESPONSE_IS_INVALID,
    ENCRYPTION_INFO_ELEMENT_NOT_FOUND,
    ORDER_DATA_ELEMENT_NOT_FOUND,
    TRANSFER_SEGMENT_FAILED,
    /**
     * This might indicate that the EBICS transaction had errors.
     */
    EBICS_UPLOAD_TRANSACTION_ID_MISSING,
    /**
     * May be caused by a connection issue OR the HTTP response
     * code was not 200 OK.  Both cases should lead to retry as
     * they are fixable or transient.
     */
    HTTP_POST_FAILED
}

/**
 * Those errors happen before getting to validate the bank response
 * and successfully verify its signature.  They bring therefore NO
 * business meaning and may be retried.
 */
class EbicsSideException(
    msg: String,
    val sideEc: EbicsSideError
) : Exception(msg)

/**
 * Parses the bank response from the raw XML and verifies
 * the bank signature.
 *
 * @param bankKeys provides the bank auth pub, to verify the signature.
 * @param responseStr raw XML response from the bank
 * @param withEbics3 true if the communication is EBICS 3, false otherwise.
 * @return [EbicsResponseContent] or throw [EbicsSideException]
 */
fun parseAndValidateEbicsResponse(
    bankKeys: BankPublicKeysFile,
    responseStr: String,
    withEbics3: Boolean
): EbicsResponseContent {
    val responseDocument = try {
        XMLUtil.parseStringIntoDom(responseStr)
    } catch (e: Exception) {
        throw EbicsSideException(
            "Bank response apparently invalid",
            sideEc = EbicsSideError.BANK_RESPONSE_IS_INVALID
        )
    }
    if (!XMLUtil.verifyEbicsDocument(
            responseDocument,
            bankKeys.bank_authentication_public_key,
            withEbics3
    )) {
        throw EbicsSideException(
            "Bank signature did not verify",
            sideEc = EbicsSideError.BANK_SIGNATURE_DIDNT_VERIFY
        )
    }
    if (withEbics3)
        return ebics3toInternalRepr(responseStr)
    return ebics25toInternalRepr(responseStr)
}

/**
 * Signs and the encrypts the data to send via EBICS.
 *
 * @param cfg configuration handle.
 * @param clientKeys client keys.
 * @param bankKeys bank keys.
 * @param payload business payload to send to the bank, typically ISO20022.
 * @param isEbics3 true if the payload travels on EBICS 3.
 * @return [PreparedUploadData]
 */
fun prepareUploadPayload(
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    payload: ByteArray,
    isEbics3: Boolean
): PreparedUploadData {
    val encryptionResult: CryptoUtil.EncryptionResult = if (isEbics3) {
        val innerSignedEbicsXml = signOrderEbics3( // A006 signature.
            payload,
            clientKeys.signature_private_key,
            cfg.ebicsPartnerId,
            cfg.ebicsUserId
        )
        val userSignatureDataEncrypted = CryptoUtil.encryptEbicsE002(
            EbicsOrderUtil.encodeOrderDataXml(innerSignedEbicsXml),
            bankKeys.bank_encryption_public_key
        )
        userSignatureDataEncrypted
    } else {
        val innerSignedEbicsXml = signOrder( // A006 signature.
            payload,
            clientKeys.signature_private_key,
            cfg.ebicsPartnerId,
            cfg.ebicsUserId
        )
        val userSignatureDataEncrypted = CryptoUtil.encryptEbicsE002(
            EbicsOrderUtil.encodeOrderDataXml(innerSignedEbicsXml),
            bankKeys.bank_encryption_public_key
        )
        userSignatureDataEncrypted
    }
    val plainTransactionKey = encryptionResult.plainTransactionKey
        ?: throw Exception("Could not generate the transaction key, cannot encrypt the payload!")
    // Then only E002 symmetric (with ephemeral key) encrypt.
    val compressedInnerPayload = DeflaterInputStream(
        payload.inputStream()
    ).use { it.readAllBytes() }
    val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
        compressedInnerPayload,
        bankKeys.bank_encryption_public_key,
        plainTransactionKey
    )
    val encodedEncryptedPayload = Base64.getEncoder().encodeToString(encryptedPayload.encryptedData)

    return PreparedUploadData(
        encryptionResult.encryptedTransactionKey, // ephemeral key
        encryptionResult.encryptedData, // bank-pub-encrypted A006 signature.
        CryptoUtil.digestEbicsOrderA006(payload), // used by EBICS 3
        listOf(encodedEncryptedPayload) // actual payload E002 encrypted.
    )
}

/**
 * Possible states of an EBICS transaction.
 */
enum class EbicsPhase {
    initialization,
    transmission,
    receipt
}

/**
 * Witnesses a failure in an EBICS communication.  That
 * implies that the bank response and its signature were
 * both valid.
 */
class EbicsUploadException(
    msg: String,
    val phase: EbicsPhase,
    val ebicsErrorCode: EbicsReturnCode,
    /**
     * If the error was EBICS-technical, then we might not
     * even have interest on the business error code, therefore
     * the value below may be null.
     */
    val bankErrorCode: EbicsReturnCode? = null
) : Exception(msg)

/**
 * Collects all the steps of an EBICS 3 upload transaction.
 * NOTE: this function could conveniently be reused for an EBICS 2.x
 * transaction, hence this function stays in this file.
 *
 * @param client HTTP client for POSTing to the bank.
 * @param cfg configuration handle.
 * @param clientKeys client EBICS private keys.
 * @param bankKeys bank EBICS public keys.
 * @param payload binary business paylaod.
 * @return [EbicsResponseContent] or throws [EbicsUploadException]
 */
suspend fun doEbicsUpload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    orderService: Ebics3Request.OrderDetails.Service,
    payload: ByteArray,
    extraLog: Boolean = false
): EbicsResponseContent {
    val preparedPayload = prepareUploadPayload(cfg, clientKeys, bankKeys, payload, isEbics3 = true)
    val initXml = createEbics3RequestForUploadInitialization(
        cfg,
        preparedPayload,
        bankKeys,
        clientKeys,
        orderService
    )
    if (extraLog) logger.debug(initXml)
    val initResp = postEbics( // may throw EbicsEarlyException
            client,
            cfg,
            bankKeys,
            initXml,
            isEbics3 = true
    )
    if (!areCodesOk(initResp)) throw EbicsUploadException(
        "EBICS upload init failed",
        phase = EbicsPhase.initialization,
        ebicsErrorCode = initResp.technicalReturnCode,
        bankErrorCode = initResp.bankReturnCode
    )
    // Init phase OK, proceeding with the transfer phase.
    val tId = initResp.transactionID
        ?: throw EbicsSideException(
            "EBICS upload init phase did not return a transaction ID, cannot do the transfer phase.",
            sideEc = EbicsSideError.EBICS_UPLOAD_TRANSACTION_ID_MISSING
        )
    val transferXml = createEbics3RequestForUploadTransferPhase(
        cfg,
        clientKeys,
        tId,
        preparedPayload
    )
    val transferResp = postEbics(
        client,
        cfg,
        bankKeys,
        transferXml,
        isEbics3 = true
    )
    if (!areCodesOk(transferResp)) throw EbicsUploadException(
        "EBICS upload transfer failed",
        phase = EbicsPhase.transmission,
        ebicsErrorCode = initResp.technicalReturnCode,
        bankErrorCode = initResp.bankReturnCode
    )
    // EBICS- and bank-technical codes were both EBICS_OK, success!
    return transferResp
}