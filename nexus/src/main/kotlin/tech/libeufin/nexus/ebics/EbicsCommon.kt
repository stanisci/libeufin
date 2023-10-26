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
import tech.libeufin.nexus.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.logger
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.DeflaterInputStream

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
 *
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
 * @return the internal representation of an EBICS response IF both return codes
 *         were EBICS_OK, or null otherwise.
 */
suspend fun postEbicsAndCheckReturnCodes(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    bankKeys: BankPublicKeysFile,
    xmlReq: String,
    isEbics3: Boolean,
    tolerateEbicsReturnCode: EbicsReturnCode? = null,
    tolerateBankReturnCode: EbicsReturnCode? = null
): EbicsResponseContent? {
    val respXml = client.postToBank(cfg.hostBaseUrl, xmlReq)
    if (respXml == null) {
        tech.libeufin.nexus.logger.error("EBICS init phase failed.  Aborting the HTD operation.")
        return null
    }
    val respObj: EbicsResponseContent = parseAndValidateEbicsResponse(
        bankKeys,
        respXml,
        isEbics3
    ) ?: return null // helper logged the cause already.

    var isEbicsCodeTolerated = false
    if (tolerateEbicsReturnCode != null)
       isEbicsCodeTolerated = respObj.technicalReturnCode == tolerateEbicsReturnCode

    // EBICS communication error.
    if ((respObj.technicalReturnCode != EbicsReturnCode.EBICS_OK) && (!isEbicsCodeTolerated)) {
        tech.libeufin.nexus.logger.error("EBICS return code is ${respObj.technicalReturnCode}, failing.")
        return null
    }
    var isBankCodeTolerated = false
    if (tolerateBankReturnCode != null)
        isBankCodeTolerated = respObj.bankReturnCode == tolerateBankReturnCode

    // Business error, although EBICS itself was correct.
    if ((respObj.bankReturnCode != EbicsReturnCode.EBICS_OK) && (!isBankCodeTolerated)) {
        tech.libeufin.nexus.logger.error("Bank-technical return code is ${respObj.technicalReturnCode}, failing.")
        return null
    }
    return respObj
}
/**
 * Collects all the steps of an EBICS download transaction.  Namely,
 * it conducts: init -> transfer -> receipt phases.
 *
 * @param client HTTP client for POSTing to the bank.
 * @param cfg configuration handle.
 * @param clientKeys client EBICS private keys.
 * @param bankKeys bank EBICS public keys.
 * @param reqXml raw EBICS XML request.
 * @return the bank response as an XML string, or null if one
 *         error took place.  NOTE: any return code other than
 *         EBICS_OK constitutes an error.
 */
suspend fun doEbicsDownload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    reqXml: String,
    isEbics3: Boolean
): String? {
    val initResp = postEbicsAndCheckReturnCodes(client, cfg, bankKeys, reqXml, isEbics3)
    if (initResp == null) {
        tech.libeufin.nexus.logger.error("EBICS download: could not get past the EBICS init phase, failing.")
        return null
    }
    val howManySegments = initResp.numSegments
    if (howManySegments == null) {
        tech.libeufin.nexus.logger.error("Init response lacks the quantity of segments, failing.")
        return null
    }
    val ebicsChunks = mutableListOf<String>()
    // Getting the chunk(s)
    val firstDataChunk = initResp.orderDataEncChunk
    if (firstDataChunk == null) {
        tech.libeufin.nexus.logger.error("Could not get the first data chunk, although the EBICS_OK return code, failing.")
        return null
    }
    val dataEncryptionInfo = initResp.dataEncryptionInfo ?: run {
        tech.libeufin.nexus.logger.error("EncryptionInfo element not found, despite non empty payload, failing.")
        return null
    }
    ebicsChunks.add(firstDataChunk)
    val tId = initResp.transactionID
    if (tId == null) {
        tech.libeufin.nexus.logger.error("Transaction ID not found in the init response, cannot do transfer phase, failing.")
        return null
    }
    // proceed with the transfer phase.
    for (x in 2 .. howManySegments) {
        // request segment number x.
        val transReq = createEbics25TransferPhase(cfg, clientKeys, x, howManySegments, tId)
        val transResp = postEbicsAndCheckReturnCodes(client, cfg, bankKeys, transReq, isEbics3)
        if (transResp == null) {
            tech.libeufin.nexus.logger.error("EBICS transfer segment #$x failed.")
            return null
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
    // payload reconstructed, ack to the bank.
    val ackXml = createEbics25ReceiptPhase(cfg, clientKeys, tId)
    val ackResp = postEbicsAndCheckReturnCodes(
        client,
        cfg,
        bankKeys,
        ackXml,
        isEbics3,
        tolerateEbicsReturnCode = EbicsReturnCode.EBICS_DOWNLOAD_POSTPROCESS_DONE
    )
    if (ackResp == null) {
        tech.libeufin.nexus.logger.error("EBICS receipt phase failed.")
        return null
    }
    // receipt phase OK, can now return the payload as an XML string.
    return try {
        payloadBytes.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        logger.error("Could not get the XML string out of payload bytes.")
        null
    }
}

/**
 * Parses the bank response from the raw XML and verifies
 * the bank signature.
 *
 * @param bankKeys provides the bank auth pub, to verify the signature.
 * @param responseStr raw XML response from the bank
 * @param withEbics3 true if the communication is EBICS 3, false otherwise.
 * @return libeufin internal representation of EBICS responses.  Null
 *         in case of errors.
 */
fun parseAndValidateEbicsResponse(
    bankKeys: BankPublicKeysFile,
    responseStr: String,
    withEbics3: Boolean
): EbicsResponseContent? {
    val responseDocument = try {
        XMLUtil.parseStringIntoDom(responseStr)
    } catch (e: Exception) {
        tech.libeufin.nexus.logger.error("Bank response apparently invalid.")
        return null
    }
    if (!XMLUtil.verifyEbicsDocument(
            responseDocument,
            bankKeys.bank_authentication_public_key,
            withEbics3
        )) {
        tech.libeufin.nexus.logger.error("Bank signature did not verify.")
        return null
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
fun prepareUloadPayload(
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
    if (plainTransactionKey == null)
        throw Exception("Could not generate the transaction key, cannot encrypt the payload!")
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
 * Collects all the steps of an EBICS 3 upload transaction.
 * NOTE: this function could conveniently be reused for an EBICS 2.x
 * transaction, hence this function stays in this file.
 *
 * @param client HTTP client for POSTing to the bank.
 * @param cfg configuration handle.
 * @param clientKeys client EBICS private keys.
 * @param bankKeys bank EBICS public keys.
 * @param payload binary business paylaod.
 * @return [EbicsResponseContent] or null upon errors.
 */
suspend fun doEbicsUpload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    orderService: Ebics3Request.OrderDetails.Service,
    payload: ByteArray
): EbicsResponseContent? {
    val preparedPayload = prepareUloadPayload(cfg, clientKeys, bankKeys, payload, isEbics3 = true)
    val initXml = createEbics3RequestForUploadInitialization(
        cfg,
        preparedPayload,
        bankKeys,
        clientKeys,
        orderService
    )
    val initResp = postEbicsAndCheckReturnCodes(
        client,
        cfg,
        bankKeys,
        initXml,
        isEbics3 = true
    )
    if (initResp == null) {
        tech.libeufin.nexus.logger.error("EBICS upload init phase failed.")
        return null
    }

    // Init phase OK, proceeding with the transfer phase.
    val tId = initResp.transactionID
    if (tId == null) {
        logger.error("EBICS upload init phase did not return a transaction ID, cannot do the transfer phase.")
        return null
    }
    val transferXml = createEbics3RequestForUploadTransferPhase(
        cfg,
        clientKeys,
        tId,
        preparedPayload
    )
    val transferResp = postEbicsAndCheckReturnCodes(
        client,
        cfg,
        bankKeys,
        transferXml,
        isEbics3 = true
    )
    if (transferResp == null) {
        tech.libeufin.nexus.logger.error("EBICS transfer phase failed.")
        return null
    }
    // EBICS- and bank-technical codes were both EBICS_OK, success!
    return transferResp
}