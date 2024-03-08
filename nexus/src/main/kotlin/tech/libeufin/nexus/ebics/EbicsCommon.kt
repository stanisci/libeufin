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
import kotlinx.coroutines.*
import java.security.SecureRandom
import org.w3c.dom.Document
import org.xml.sax.SAXException

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
suspend fun HttpClient.postToBank(bankUrl: String, msg: ByteArray): Document {
    val res = try {
        post(urlString = bankUrl) {
            contentType(ContentType.Text.Xml)
            setBody(msg)
        }
    } catch (e: Exception) {
        throw EbicsError.Transport("failed to contact bank", e)
    }
    
    if (res.status != HttpStatusCode.OK) {
        throw EbicsError.Transport("bank HTTP error: ${res.status}")
    }
    try {
        return XMLUtil.parseIntoDom(res.bodyAsChannel().toInputStream())
    } catch (e: SAXException) {
        throw EbicsError.Protocol("invalid XML bank reponse", e)
    } catch (e: Exception) {
        throw EbicsError.Transport("failed read bank response", e)
    }
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
    xmlReq: ByteArray
): EbicsResponse {
    val doc = client.postToBank(cfg.hostBaseUrl, xmlReq)
    if (!XMLUtil.verifyEbicsDocument(
        doc,
        bankKeys.bank_authentication_public_key,
        true
    )) {
        throw EbicsError.Protocol("bank signature did not verify")
    }
    try {
        return Ebics3BTS.parseResponse(doc)
    } catch (e: Exception) {
        throw EbicsError.Protocol("invalid ebics response", e)
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
    reqXml: ByteArray,
    processing: (InputStream) -> Unit
) = coroutineScope {
    val impl = Ebics3BTS(cfg, bankKeys, clientKeys)
    val parentScope = this
    
    // We need to run the logic in a non-cancelable context because we need to send 
    // a receipt for each open download transaction, otherwise we'll be stuck in an 
    // error loop until the pending transaction timeout.
    // TODO find a way to cancel the pending transaction ?
    withContext(NonCancellable) {
        // Init phase
        val initResp = postEbics(client, cfg, bankKeys, reqXml)
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
            postEbics(
                client,
                cfg,
                bankKeys,
                impl.downloadReceipt(tId, success)
            ).okOrFail("Download receipt phase")
        }
        /** Throw if parent scope have been canceled */
        suspend fun checkCancellation() {
            if (!parentScope.isActive) {
                // First send a proper EBICS transaction failure
                receipt(false)
                // Send throw cancelation exception
                throw CancellationException()
            }
        }

        // Transfer phase
        val ebicsChunks = mutableListOf(firstDataChunk)
        for (x in 2 .. howManySegments) {
            checkCancellation()
            val transReq = impl.downloadTransfer(x, howManySegments, tId)
            val transResp = postEbics(client, cfg, bankKeys, transReq).okOrFail("Download transfer phase")
            val chunk = requireNotNull(transResp.payloadChunk) {
                "Download transfer phase: missing encrypted chunk"
            }
            ebicsChunks.add(chunk)
        }

        checkCancellation()

        // Decompress encrypted chunks
        val payloadBytes = try {
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
            processing(payloadBytes)
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
    val encodedEncryptedPayload = encryptedPayload.encryptedData.encodeBase64()

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
 * @return [EbicsResponseContent] or throws [EbicsUploadException]
 */
suspend fun doEbicsUpload(
    client: HttpClient,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile,
    service: Ebics3Service,
    payload: ByteArray,
): String = withContext(NonCancellable) {
    val impl = Ebics3BTS(cfg, bankKeys, clientKeys)
    // TODO use a lambda and pass the order detail there for atomicity ?
    val preparedPayload = prepareUploadPayload(cfg, clientKeys, bankKeys, payload)
    
    // Init phase
    val initXml = impl.uploadInitialization(service, preparedPayload)
    val initResp = postEbics(client, cfg, bankKeys, initXml).okOrFail("Upload init phase")
    val tId = requireNotNull(initResp.transactionID) {
        "Upload init phase: missing transaction ID"
    }

    // Transfer phase
    val transferXml = impl.uploadTransfer(tId, preparedPayload)
    val transferResp = postEbics(client, cfg, bankKeys, transferXml).okOrFail("Upload transfer phase")
    val orderId = requireNotNull(transferResp.orderID) {
        "Upload transfer phase: missing order ID"
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
    val encryptedPayloadChunks: List<String>
)

class DataEncryptionInfo(
    val transactionKey: ByteArray,
    val bankPubDigest: ByteArray
)

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
    pain001xml: ByteArray,
    cfg: EbicsSetupConfig,
    clientKeys: ClientPrivateKeysFile,
    bankkeys: BankPublicKeysFile,
    httpClient: HttpClient
): String {
    val service = Ebics3Service(
        name = "MCT",
        scope = "CH",
        messageName = "pain.001",
        messageVersion = "09",
        container = null
    )
    return doEbicsUpload(
        httpClient,
        cfg,
        clientKeys,
        bankkeys,
        service,
        pain001xml,
    )
}

// TODO import missing using a script
@Suppress("SpellCheckingInspection")
enum class EbicsReturnCode(val code: String) {
    EBICS_OK("000000"),
    EBICS_DOWNLOAD_POSTPROCESS_DONE("011000"),
    EBICS_DOWNLOAD_POSTPROCESS_SKIPPED("011001"),
    EBICS_TX_SEGMENT_NUMBER_UNDERRUN("011101"),
    EBICS_AUTHENTICATION_FAILED("061001"),
    EBICS_INVALID_REQUEST("061002"),
    EBICS_INTERNAL_ERROR("061099"),
    EBICS_TX_RECOVERY_SYNC("061101"),
    EBICS_AUTHORISATION_ORDER_IDENTIFIER_FAILED("090003"),
    EBICS_INVALID_ORDER_DATA_FORMAT("090004"),
    EBICS_NO_DOWNLOAD_DATA_AVAILABLE("090005"),
    EBICS_INVALID_USER_OR_USER_STATE("091002"),
    EBICS_USER_UNKNOWN("091003"),
    EBICS_EBICS_INVALID_USER_STATE("091004"),
    EBICS_INVALID_ORDER_IDENTIFIER("091005"),
    EBICS_UNSUPPORTED_ORDER_TYPE("091006"),
    EBICS_INVALID_XML("091010"),
    EBICS_TX_MESSAGE_REPLAY("091103"),
    EBICS_INVALID_REQUEST_CONTENT("091113"),
    EBICS_PROCESSING_ERROR("091116"),
    EBICS_ACCOUNT_AUTHORISATION_FAILED("091302"),
    EBICS_AMOUNT_CHECK_FAILED("091303");

    enum class Kind {
        Information,
        Note,
        Warning,
        Error
    }

    fun kind(): Kind {
        return when (val errorClass = code.substring(0..1)) {
            "00" -> Kind.Information
            "01" -> Kind.Note
            "03" -> Kind.Warning
            "06", "09" -> Kind.Error
            else -> throw Exception("Unknown EBICS status code error class: $errorClass")
        }
    }

    companion object {
        fun lookup(code: String): EbicsReturnCode {
            for (x in entries) {
                if (x.code == code) {
                    return x
                }
            }
            throw Exception(
                "Unknown EBICS status code: $code"
            )
        }
    }
}