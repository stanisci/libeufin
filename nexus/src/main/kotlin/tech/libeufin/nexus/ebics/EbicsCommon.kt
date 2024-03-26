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

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import tech.libeufin.nexus.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.time.Instant
import kotlinx.coroutines.*
import java.security.SecureRandom
import org.w3c.dom.Document
import org.xml.sax.SAXException

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
 * @return the plain payload.
 */
fun decryptAndDecompressPayload(
    clientEncryptionKey: RSAPrivateCrtKey,
    encryptionInfo: DataEncryptionInfo,
    chunks: List<ByteArray>
): InputStream =
    SequenceInputStream(Collections.enumeration(chunks.map { it.inputStream() })) // Aggregate
        .run {
            CryptoUtil.decryptEbicsE002(
                encryptionInfo.transactionKey,
                this,
                clientEncryptionKey
            )
        }.inflate()

sealed class EbicsError(msg: String, cause: Throwable? = null): Exception(msg, cause) {
    /** Http and network errors */
    class Transport(msg: String, cause: Throwable? = null): EbicsError(msg, cause)
    /** EBICS protocol & XML format error */
    class Protocol(msg: String, cause: Throwable? = null): EbicsError(msg, cause)
}

/**
 * POSTs the EBICS message to the bank.
 *
 * @param URL where the bank serves EBICS requests.
 * @param msg EBICS message as raw bytes.
 * @return the raw bank response.
 */
suspend fun HttpClient.postToBank(bankUrl: String, msg: ByteArray, phase: String): Document {
    val res = try {
        post(urlString = bankUrl) {
            contentType(ContentType.Text.Xml)
            setBody(msg)
        }
    } catch (e: Exception) {
        throw EbicsError.Transport("$phase: failed to contact bank", e)
    }
    
    if (res.status != HttpStatusCode.OK) {
        throw EbicsError.Transport("$phase: bank HTTP error: ${res.status}")
    }
    try {
        return XMLUtil.parseIntoDom(res.bodyAsChannel().toInputStream())
    } catch (e: SAXException) {
        throw EbicsError.Protocol("$phase: invalid XML bank response", e)
    } catch (e: Exception) {
        throw EbicsError.Transport("$phase: failed read bank response", e)
    }
}

suspend fun EbicsBTS.postBTS(
    client: HttpClient,
    xmlReq: ByteArray,
    phase: String,
): EbicsResponse<BTSResponse> {
    val doc = client.postToBank(cfg.hostBaseUrl, xmlReq, phase)
    if (!XMLUtil.verifyEbicsDocument(
        doc,
        bankKeys.bank_authentication_public_key,
        order.schema
    )) {
        throw EbicsError.Protocol("$phase: bank signature did not verify")
    }
    try {
        return EbicsBTS.parseResponse(doc)
    } catch (e: Exception) {
        throw EbicsError.Protocol("$phase: invalid ebics response", e)
    }
}

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
 * @param processing processing lambda receiving EBICS files as a byte stream if the transaction was not empty.
 * @return T if the transaction was successful. If the failure is at the EBICS 
 *         level EbicsSideException is thrown else ités the exception of the processing lambda.
 */
suspend fun ebicsDownload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    order: EbicsOrder,
    startDate: Instant?, 
    endDate: Instant?,
    processing: suspend (InputStream) -> Unit,
) = coroutineScope {
    val impl = EbicsBTS(cfg, bankKeys, clientKeys, order)
    val parentScope = this
    
    // We need to run the logic in a non-cancelable context because we need to send 
    // a receipt for each open download transaction, otherwise we'll be stuck in an 
    // error loop until the pending transaction timeout.
    // TODO find a way to cancel the pending transaction ?
    withContext(NonCancellable) {
        // Init phase
        val initReq = impl.downloadInitialization(startDate, endDate)
        val initResp = impl.postBTS(client, initReq, "Download init phase")
        if (initResp.bankCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE) {
           logger.debug("Download content is empty")
           return@withContext
        }
        val initContent = initResp.okOrFail("Download init phase")
        val tId = requireNotNull(initContent.transactionID) {
            "Download init phase: missing transaction ID"
        }
        val howManySegments = requireNotNull(initContent.numSegments) {
            "Download init phase: missing num segments"
        }
        val firstDataChunk = requireNotNull(initContent.payloadChunk) {
            "Download init phase: missing OrderData"
        }
        val dataEncryptionInfo = requireNotNull(initContent.dataEncryptionInfo) {
            "Download init phase: missing EncryptionInfo"
        }

        logger.debug("Download init phase for transaction '$tId'")
    
        /** Send download receipt */
        suspend fun receipt(success: Boolean) {
            val xml = impl.downloadReceipt(tId, success)
            impl.postBTS(client, xml, "Download receipt phase").okOrFail("Download receipt phase")
        }
        /** Throw if parent scope have been canceled */
        suspend fun checkCancellation() {
            if (!parentScope.isActive) {
                // First send a proper EBICS transaction failure
                receipt(false)
                // Send throw cancellation exception
                throw CancellationException()
            }
        }

        // Transfer phase
        val ebicsChunks = mutableListOf(firstDataChunk)
        for (x in 2 .. howManySegments) {
            checkCancellation()
            val transReq = impl.downloadTransfer(x, howManySegments, tId)
            val transResp = impl.postBTS(client, transReq, "Download transfer phase").okOrFail("Download transfer phase")
            val chunk = requireNotNull(transResp.payloadChunk) {
                "Download transfer phase: missing encrypted chunk"
            }
            ebicsChunks.add(chunk)
        }

        checkCancellation()

        // Decompress encrypted chunks
        val payloadStream = try {
            decryptAndDecompressPayload(
                clientKeys.encryption_private_key,
                dataEncryptionInfo,
                ebicsChunks
            )
        } catch (e: Exception) {
            throw EbicsError.Protocol("invalid chunks", e)
        }

        checkCancellation()

        // Run business logic
        val res = runCatching {
            processing(payloadStream)
        }

        // First send a proper EBICS transaction receipt
        receipt(res.isSuccess)
        // Then throw business logic exception if any
        res.getOrThrow()
    }
    Unit
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
): PreparedUploadData {
    val innerSignedEbicsXml = XmlBuilder.toBytes("UserSignatureData") {
        attr("xmlns", "http://www.ebics.org/S002")
        el("OrderSignatureData") {
            el("SignatureVersion", "A006")
            el("SignatureValue", CryptoUtil.signEbicsA006(
                CryptoUtil.digestEbicsOrderA006(payload),
                clientKeys.signature_private_key,
            ).encodeBase64())
            el("PartnerID", cfg.ebicsPartnerId)
            el("UserID", cfg.ebicsUserId)
        }
    }
    val encryptionResult = CryptoUtil.encryptEbicsE002(
        innerSignedEbicsXml.inputStream().deflate(),
        bankKeys.bank_encryption_public_key
    )
    // Then only E002 symmetric (with ephemeral key) encrypt.
    val compressedInnerPayload = payload.inputStream().deflate()
    // TODO stream
    val encryptedPayload = CryptoUtil.encryptEbicsE002withTransactionKey(
        compressedInnerPayload,
        bankKeys.bank_encryption_public_key,
        encryptionResult.plainTransactionKey
    )
    val segment = encryptedPayload.encryptedData.encodeBase64()
    // Split 1MB segment when we have payloads that big

    return PreparedUploadData(
        encryptionResult.encryptedTransactionKey, // ephemeral key
        encryptionResult.encryptedData, // bank-pub-encrypted A006 signature.
        CryptoUtil.digestEbicsOrderA006(payload), // used by EBICS 3
        listOf(segment) // actual payload E002 encrypted.
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
 * @return [EbicsResponseContent] or throws [EbicsUploadException]
 */
suspend fun doEbicsUpload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    order: EbicsOrder,
    payload: ByteArray,
): String = withContext(NonCancellable) {
    val impl = EbicsBTS(cfg, bankKeys, clientKeys, order)
    // TODO use a lambda and pass the order detail there for atomicity ?
    val preparedPayload = prepareUploadPayload(cfg, clientKeys, bankKeys, payload)
    
    // Init phase
    val initXml = impl.uploadInitialization(preparedPayload)
    val initResp = impl.postBTS(client, initXml, "Upload init phase").okOrFail("Upload init phase")
    val tId = requireNotNull(initResp.transactionID) {
        "Upload init phase: missing transaction ID"
    }
    val orderId = requireNotNull(initResp.orderID) {
        "Upload init phase: missing order ID"
    }

    // Transfer phase
    for (i in 1..preparedPayload.segments.size) {
        val transferXml = impl.uploadTransfer(tId, preparedPayload, i)
        val transferResp = impl.postBTS(client, transferXml, "Upload transfer phase").okOrFail("Upload transfer phase")
    }
    orderId
}

/**
 * @param size in bits
 */
fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}

class PreparedUploadData(
    val transactionKey: ByteArray,
    val userSignatureDataEncrypted: ByteArray,
    val dataDigest: ByteArray,
    val segments: List<String>
)

class DataEncryptionInfo(
    val transactionKey: ByteArray,
    val bankPubDigest: ByteArray
)

class EbicsResponse<T>(
    val technicalCode: EbicsReturnCode,
    val bankCode: EbicsReturnCode,
    private val content: T
) {
    /** Checks that return codes are both EBICS_OK or throw an exception */
    fun okOrFail(phase: String): T {
        logger.debug("$phase return codes: $technicalCode & $bankCode")
        require(technicalCode.kind() != EbicsReturnCode.Kind.Error) {
            "$phase has technical error: $technicalCode"
        }
        require(bankCode.kind() != EbicsReturnCode.Kind.Error) {
            "$phase has bank error: $bankCode"
        }
        return content
    }
}