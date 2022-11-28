/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

/**
 * High-level interface for the EBICS protocol.
 */
package tech.libeufin.nexus.ebics

import io.ktor.client.HttpClient
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.NexusError
import tech.libeufin.util.*
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util")

private suspend inline fun HttpClient.postToBank(url: String, body: String): String {
    // logger.debug("Posting: $body")
    if (!XMLUtil.validateFromString(body)) throw NexusError(
        HttpStatusCode.InternalServerError,
        "EBICS (outgoing) document is invalid"
    )
    val response: String = try {
        this.post(
            urlString = url,
            block = {
                this.body = body
            }
        )
    } catch (e: ClientRequestException) {
        logger.error(e.message)
        throw NexusError(
            HttpStatusCode.BadGateway,
            e.message
        )
    }
    catch (e: Exception) {
        logger.error("Exception during request", e)
        throw NexusError(
            HttpStatusCode.BadGateway,
            e.message ?: "Could not reach the bank"
        )
    }
    // logger.debug("Receiving: $response")
    return response
}

sealed class EbicsDownloadResult

class EbicsDownloadSuccessResult(
    val orderData: ByteArray
) : EbicsDownloadResult()

/**
 * Some bank-technical error occured.
 */
class EbicsDownloadBankErrorResult(
    val returnCode: EbicsReturnCode
) : EbicsDownloadResult()

/**
 * Do an EBICS download transaction.  This includes the initialization phase, transaction phase
 * and receipt phase.
 */
suspend fun doEbicsDownloadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    orderParams: EbicsOrderParams
): EbicsDownloadResult {

    // Initialization phase
    val initDownloadRequestStr = createEbicsRequestForDownloadInitialization(subscriberDetails, orderType, orderParams)
    val payloadChunks = LinkedList<String>()
    val initResponseStr = client.postToBank(subscriberDetails.ebicsUrl, initDownloadRequestStr)
    val initResponse = parseAndValidateEbicsResponse(subscriberDetails, initResponseStr)

    when (initResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            // Success, nothing to do!
        }
        else -> {
            throw EbicsProtocolError(
                HttpStatusCode.InternalServerError,
                "unexpected return code ${initResponse.technicalReturnCode}",
                initResponse.technicalReturnCode
            )
        }
    }

    when (initResponse.bankReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            // Success, nothing to do!
        }
        else -> {
            logger.warn("Bank return code was: ${initResponse.bankReturnCode}")
            return EbicsDownloadBankErrorResult(initResponse.bankReturnCode)
        }
    }

    val transactionID =
        initResponse.transactionID ?: throw NexusError(
            HttpStatusCode.InternalServerError,
            "Initial response must contain transaction ID"
        )
    logger.debug("Bank acknowledges EBICS download initialization.  Transaction ID: $transactionID.")
    val encryptionInfo = initResponse.dataEncryptionInfo
        ?: throw NexusError(HttpStatusCode.InternalServerError, "initial response did not contain encryption info")

    val initOrderDataEncChunk = initResponse.orderDataEncChunk
        ?: throw NexusError(
            HttpStatusCode.InternalServerError,
            "initial response for download transaction does not contain data transfer"
        )

    payloadChunks.add(initOrderDataEncChunk)

    val numSegments = initResponse.numSegments
        ?: throw NexusError(HttpStatusCode.FailedDependency, "missing segment number in EBICS download init response")

    // Transfer phase
    for (x in 2 .. numSegments) {
        val transferReqStr =
            createEbicsRequestForDownloadTransferPhase(subscriberDetails, transactionID, x, numSegments)
        logger.debug("EBICS download transfer phase of ${transactionID}: sending segment $x")
        val transferResponseStr = client.postToBank(subscriberDetails.ebicsUrl, transferReqStr)
        val transferResponse = parseAndValidateEbicsResponse(subscriberDetails, transferResponseStr)
        when (transferResponse.technicalReturnCode) {
            EbicsReturnCode.EBICS_OK -> {
                // Success, nothing to do!
            }
            else -> {
                throw NexusError(
                    HttpStatusCode.FailedDependency,
                    "unexpected technical return code ${transferResponse.technicalReturnCode}"
                )
            }
        }
        when (transferResponse.bankReturnCode) {
            EbicsReturnCode.EBICS_OK -> {
                // Success, nothing to do!
            }
            else -> {
                logger.warn("Bank return code was: ${transferResponse.bankReturnCode}")
                return EbicsDownloadBankErrorResult(transferResponse.bankReturnCode)
            }
        }
        val transferOrderDataEncChunk = transferResponse.orderDataEncChunk
            ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "transfer response for download transaction does not contain data transfer"
            )
        payloadChunks.add(transferOrderDataEncChunk)
        logger.debug("Download transfer phase of ${transactionID}: bank acknowledges $x")
    }

    val respPayload = decryptAndDecompressResponse(subscriberDetails, encryptionInfo, payloadChunks)

    // Acknowledgement phase

    val ackRequest = createEbicsRequestForDownloadReceipt(subscriberDetails, transactionID)
    val ackResponseStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        ackRequest
    )
    val ackResponse = parseAndValidateEbicsResponse(subscriberDetails, ackResponseStr)
    when (ackResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_DOWNLOAD_POSTPROCESS_DONE -> {
        }
        else -> {
            throw NexusError(
                HttpStatusCode.InternalServerError,
                "unexpected return code: ${ackResponse.technicalReturnCode.name}"
            )
        }
    }
    logger.debug("Bank acknowledges EBICS download receipt.  Transaction ID: $transactionID.")
    return EbicsDownloadSuccessResult(respPayload)
}

/**
 * Currently only 1-segment requests.
 */
suspend fun doEbicsUploadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    orderType: String,
    payload: ByteArray,
    orderParams: EbicsOrderParams
) {
    if (subscriberDetails.bankEncPub == null) {
        throw NexusError(HttpStatusCode.BadRequest, "bank encryption key unknown, request HPB first")
    }
    val preparedUploadData = prepareUploadPayload(subscriberDetails, payload)
    val req = createEbicsRequestForUploadInitialization(subscriberDetails, orderType, orderParams, preparedUploadData)
    val responseStr = client.postToBank(subscriberDetails.ebicsUrl, req)

    val initResponse = parseAndValidateEbicsResponse(subscriberDetails, responseStr)
    if (initResponse.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        throw NexusError(HttpStatusCode.InternalServerError, reason = "unexpected return code")
    }

    val transactionID =
        initResponse.transactionID ?: throw NexusError(
            HttpStatusCode.InternalServerError,
            "init response must have transaction ID"
        )
    logger.debug("Bank acknowledges EBICS upload initialization.  Transaction ID: $transactionID.")
    /* now send actual payload */

    val payload = createEbicsRequestForUploadTransferPhase(
        subscriberDetails,
        transactionID,
        preparedUploadData,
        0
    )
    val txRespStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        payload
    )

    val txResp = parseAndValidateEbicsResponse(subscriberDetails, txRespStr)
    when (txResp.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
        }
        else -> {
            throw NexusError(HttpStatusCode.InternalServerError,
                "Unexpected EBICS return code: ${txResp.technicalReturnCode}"
            )
        }
    }
    logger.debug("Bank acknowledges EBICS upload transfer.  Transaction ID: $transactionID")
}

suspend fun doEbicsHostVersionQuery(client: HttpClient, ebicsBaseUrl: String, ebicsHostId: String): EbicsHevDetails {
    val ebicsHevRequest = makeEbicsHEVRequestRaw(ebicsHostId)
    val resp = client.postToBank(ebicsBaseUrl, ebicsHevRequest)
    return parseEbicsHEVResponse(resp)
}

suspend fun doEbicsIniRequest(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails
): EbicsKeyManagementResponseContent {
    val request = makeEbicsIniRequest(subscriberDetails)
    val respStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        request
    )
    return parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, respStr)
}

suspend fun doEbicsHiaRequest(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails
): EbicsKeyManagementResponseContent {
    val request = makeEbicsHiaRequest(subscriberDetails)
    val respStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        request
    )
    return parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, respStr)
}


suspend fun doEbicsHpbRequest(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails
): HpbResponseData {
    val request = makeEbicsHpbRequest(subscriberDetails)
    val respStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        request
    )
    val parsedResponse = parseAndDecryptEbicsKeyManagementResponse(subscriberDetails, respStr)
    val orderData = parsedResponse.orderData ?: throw EbicsProtocolError(
        HttpStatusCode.BadGateway,
        "Cannot find data in a HPB response"
    )
    return parseEbicsHpbOrder(orderData)
}
