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
import io.ktor.utils.io.jvm.javaio.*
import tech.libeufin.common.*
import tech.libeufin.ebics.*
import tech.libeufin.ebics.ebics_h005.Ebics3Request
import tech.libeufin.nexus.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Available EBICS versions.
 */
enum class EbicsVersion { two, three }

/**
 * Which documents can be downloaded via EBICS.
 */
enum class SupportedDocument {
    PAIN_002,
    PAIN_002_LOGS,
    CAMT_053,
    CAMT_052,
    CAMT_054
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
): InputStream =
    SequenceInputStream(Collections.enumeration(chunks.map { it.toByteArray().inputStream() })) // Aggregate
        .decodeBase64()
        .run {
            CryptoUtil.decryptEbicsE002(
                encryptionInfo.transactionKey,
                this,
                clientEncryptionKey
            )
        }.inflate()

/**
 * POSTs the EBICS message to the bank.
 *
 * @param URL where the bank serves EBICS requests.
 * @param msg EBICS message as raw bytes.
 * @return the raw bank response.
 */
suspend fun HttpClient.postToBank(bankUrl: String, msg: ByteArray): InputStream {
    logger.debug("POSTing EBICS to '$bankUrl'")
    val res = post(urlString = bankUrl) {
        contentType(ContentType.Text.Xml)
        setBody(msg)
    }
    if (res.status != HttpStatusCode.OK) {
        throw Exception("Invalid response status: ${res.status}")
    }
    return res.bodyAsChannel().toInputStream()
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
 * @param xmlReq raw EBICS request in XML.
 * @param isEbics3 true in case the communication is EBICS 3, false 
 * @return [EbicsResponseContent] or throws [EbicsSideException]
 */
suspend fun postEbics(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    bankKeys: BankPublicKeysFile,
    xmlReq: ByteArray,
    isEbics3: Boolean
): EbicsResponseContent {
    val respXml = try {
        client.postToBank(cfg.hostBaseUrl, xmlReq)
    } catch (e: Exception) {
        throw EbicsSideException(
            "POSTing to ${cfg.hostBaseUrl} failed",
            sideEc = EbicsSideError.HTTP_POST_FAILED,
            e
        )
    }
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
 * Perform an EBICS download transaction.
 * 
 * It conducts init -> transfer -> processing -> receipt phases.
 *
 * @param client HTTP client for POSTing to the bank.
 * @param cfg configuration handle.
 * @param clientKeys client EBICS private keys.
 * @param bankKeys bank EBICS public keys.
 * @param reqXml raw EBICS XML request of the init phase.
 * @param isEbics3 true for EBICS 3, false otherwise.
 * @param processing processing lambda receiving EBICS files as a byte stream if the transaction was not empty.
 * @return T if the transaction was successful. If the failure is at the EBICS 
 *         level EbicsSideException is thrown else ités the exception of the processing lambda.
 */
suspend fun ebicsDownload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    reqXml: ByteArray,
    isEbics3: Boolean,
    processing: (InputStream) -> Unit
) {
    val initResp = postEbics(client, cfg, bankKeys, reqXml, isEbics3)
    logger.debug("Download init phase done.  EBICS- and bank-technical codes are: ${initResp.technicalReturnCode}, ${initResp.bankReturnCode}")
    if (initResp.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        throw Exception("Download init phase has EBICS-technical error: ${initResp.technicalReturnCode}")
    }
    if (initResp.bankReturnCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE) {
        logger.debug("Download content is empty")
        return
    }
    if (initResp.bankReturnCode != EbicsReturnCode.EBICS_OK) {
        throw Exception("Download init phase has bank-technical error: ${initResp.bankReturnCode}")
    }
    val tId = initResp.transactionID
        ?: throw EbicsSideException(
            "EBICS download init phase did not return a transaction ID, cannot do the transfer phase.",
            sideEc = EbicsSideError.EBICS_UPLOAD_TRANSACTION_ID_MISSING
        )
    logger.debug("EBICS download transaction passed the init phase, got ID: $tId")
    val howManySegments = initResp.numSegments
    if (howManySegments == null) {
        throw Exception("Init response lacks the quantity of segments, failing.")
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
            throw Exception("EBICS transfer phase lacks chunk #$x, failing.")
        }
        ebicsChunks.add(chunk)
    }
    // all chunks gotten, shaping a meaningful response now.
    val payloadBytes = decryptAndDecompressPayload(
        clientKeys.encryption_private_key,
        dataEncryptionInfo,
        ebicsChunks
    )
    // Process payload
    val res = runCatching {
        processing(payloadBytes)
    }
    // payload reconstructed, receipt to the bank.
    val success = res.isSuccess
    val receiptXml = if (isEbics3)
        createEbics3DownloadReceiptPhase(cfg, clientKeys, tId, success)
    else createEbics25DownloadReceiptPhase(cfg, clientKeys, tId, success)

    // Sending the receipt to the bank.
    postEbics(
        client,
        cfg,
        bankKeys,
        receiptXml,
        isEbics3
    )
    // Receipt didn't throw, can now return the payload.
    return res.getOrThrow()
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
    val sideEc: EbicsSideError,
    cause: Exception? = null
) : Exception(msg, cause)

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
    resp: InputStream,
    withEbics3: Boolean
): EbicsResponseContent {
    val doc = try {
        XMLUtil.parseIntoDom(resp)
    } catch (e: Exception) {
        throw EbicsSideException(
            "Bank response apparently invalid",
            sideEc = EbicsSideError.BANK_RESPONSE_IS_INVALID
        )
    }
    if (!XMLUtil.verifyEbicsDocument(
        doc,
        bankKeys.bank_authentication_public_key,
        withEbics3
    )) {
        throw EbicsSideException(
            "Bank signature did not verify",
            sideEc = EbicsSideError.BANK_SIGNATURE_DIDNT_VERIFY
        )
    }
    if (withEbics3)
        return ebics3toInternalRepr(doc)
    return ebics25toInternalRepr(doc)
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
    val compressedInnerPayload = payload.inputStream().deflate().readAllBytes()
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
): EbicsResponseContent {
    val preparedPayload = prepareUploadPayload(cfg, clientKeys, bankKeys, payload, isEbics3 = true)
    val initXml = createEbics3RequestForUploadInitialization(
        cfg,
        preparedPayload,
        bankKeys,
        clientKeys,
        orderService
    )
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