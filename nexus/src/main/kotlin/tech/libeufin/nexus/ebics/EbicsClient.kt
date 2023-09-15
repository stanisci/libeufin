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
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.nexus.NexusError
import tech.libeufin.util.*
import java.util.*

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util")

private suspend inline fun HttpClient.postToBank(url: String, body: String): String {
    if (!XMLUtil.validateFromString(body)) throw NexusError(
        HttpStatusCode.InternalServerError,
        "EBICS (outgoing) document is invalid"
    )
    val response: HttpResponse = try {
        this.post(urlString = url) {
                setBody(body)
        }
    } catch (e: ClientRequestException) {
        logger.error("Exception during request to $url: ${e.message}")
        val returnStatus = if (e.response.status.value == HttpStatusCode.RequestTimeout.value)
            HttpStatusCode.GatewayTimeout
        else HttpStatusCode.BadGateway
        throw NexusError(
            returnStatus,
            e.message
        )
    }
    catch (e: Exception) {
        logger.error("Exception during request to $url: ${e.message}")
        throw NexusError(
            HttpStatusCode.BadGateway,
            e.message ?: "Could not reach the bank"
        )
    }
    /**
     * EBICS should be expected only after a 200 OK response
     * (including problematic ones); throw exception in all the other cases,
     * by echoing what the bank said.
     */
    if (response.status.value != HttpStatusCode.OK.value)
        throw NexusError(
            HttpStatusCode.BadGateway,
            "bank says: ${response.bodyAsText()}"
        )
    return response.bodyAsText()
}

sealed class EbicsDownloadResult

class EbicsDownloadSuccessResult(
    val orderData: ByteArray,
    /**
     * This value points at the EBICS transaction that carried
     * the order data contained in this structure.  That makes
     * possible to log the EBICS transaction that carried one
     * invalid order data, for example.
     */
    val transactionID: String? = null
) : EbicsDownloadResult()

class EbicsDownloadEmptyResult(
    val orderData: ByteArray = ByteArray(0)
) : EbicsDownloadResult()


/**
 * A bank-technical error occurred.
 */
class EbicsDownloadBankErrorResult(
    val returnCode: EbicsReturnCode
) : EbicsDownloadResult()

/**
 * Do an EBICS download transaction.  This includes
 * the initialization phase, transaction phase and receipt phase.
 */
suspend fun doEbicsDownloadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    fetchSpec: EbicsFetchSpec
): EbicsDownloadResult {

    // Initialization phase
    val initDownloadRequestStr = if (fetchSpec.isEbics3) {
        if (fetchSpec.ebics3Service == null)
            throw internalServerError("Expected EBICS 3 fetch spec but null was found.")
        createEbicsRequestForDownloadInitialization(
            subscriberDetails,
            fetchSpec.ebics3Service,
            fetchSpec.orderParams
        )
    }
    else {
        if (fetchSpec.orderType == null)
            throw internalServerError("Expected EBICS 2.5 order type but null was found.")
        createEbicsRequestForDownloadInitialization(
            subscriberDetails,
            fetchSpec.orderType,
            fetchSpec.orderParams
        )
    }
    val payloadChunks = LinkedList<String>()
    val initResponseStr = client.postToBank(subscriberDetails.ebicsUrl, initDownloadRequestStr)
    val initResponse = parseAndValidateEbicsResponse(
        subscriberDetails,
        initResponseStr,
        withEbics3 = fetchSpec.isEbics3
    )
    val transactionID: String? = initResponse.transactionID
    // Checking for EBICS communication problems.
    when (initResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            /**
             * The EBICS communication succeeded, but business problems
             * may be reported along the 'bank technical' code; this check
             * takes place later.
             */
        }
        else -> {
            // The bank gave a valid XML response but EBICS had problems.
            throw EbicsProtocolError(
                HttpStatusCode.UnprocessableEntity,
                "EBICS-technical error at init phase: " +
                        "${initResponse.technicalReturnCode} ${initResponse.reportText}," +
                        " for fetching level ${fetchSpec.originalLevel} and transaction ID: $transactionID.",
                initResponse.technicalReturnCode
            )
        }
    }
    // Checking the 'bank technical' code.
    when (initResponse.bankReturnCode) {
        EbicsReturnCode.EBICS_OK -> {
            // Success, nothing to do!
        }
        EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE -> {
            // The 'pf' dialect might respond this value here (at init phase),
            // in contrast to what the default dialect does (waiting the transfer phase)
            return EbicsDownloadEmptyResult()
        }
        else -> {
            println("Bank raw response: $initResponseStr")
            logger.error(
                "Bank-technical error at init phase: ${initResponse.bankReturnCode}" +
                        ", for fetching level ${fetchSpec.originalLevel} and transaction ID $transactionID."
            )
            return EbicsDownloadBankErrorResult(initResponse.bankReturnCode)
        }
    }
    logger.debug("Bank acknowledges EBICS download initialization." +
            "  Transaction ID: $transactionID.")
    val encryptionInfo = initResponse.dataEncryptionInfo
        ?: throw NexusError(
            HttpStatusCode.BadGateway,
            "Initial response did not contain encryption info.  " +
                    "Fetching level ${fetchSpec.originalLevel} , transaction ID $transactionID"
        )

    val initOrderDataEncChunk = initResponse.orderDataEncChunk
        ?: throw NexusError(
            HttpStatusCode.BadGateway,
            "Initial response for download transaction does not " +
                    "contain data transfer.  Fetching level ${fetchSpec.originalLevel}, " +
                    "transaction ID $transactionID."
        )
    payloadChunks.add(initOrderDataEncChunk)

    val numSegments = initResponse.numSegments
        ?: throw NexusError(
            HttpStatusCode.FailedDependency,
            "Missing segment number in EBICS download init response." +
                    "  Fetching level ${fetchSpec.originalLevel}, transaction ID $transactionID"
        )
    // Transfer phase
    for (x in 2 .. numSegments) {
        val transferReqStr =
            createEbicsRequestForDownloadTransferPhase(
                subscriberDetails,
                transactionID,
                x,
                numSegments,
                fetchSpec.isEbics3
            )
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
                    "EBICS-technical error at transfer phase: " +
                            "${transferResponse.technicalReturnCode} ${transferResponse.reportText}." +
                            "  Fetching level ${fetchSpec.originalLevel}, transaction ID $transactionID"
                )
            }
        }
        when (transferResponse.bankReturnCode) {
            EbicsReturnCode.EBICS_OK -> {
                // Success, nothing to do!
            }
            else -> {
                logger.error("Bank-technical error at transfer phase: " +
                        "${transferResponse.bankReturnCode}." +
                        "  Fetching level ${fetchSpec.originalLevel}, transaction ID $transactionID")
                return EbicsDownloadBankErrorResult(transferResponse.bankReturnCode)
            }
        }
        val transferOrderDataEncChunk = transferResponse.orderDataEncChunk
            ?: throw NexusError(
                HttpStatusCode.BadGateway,
                "transfer response for download transaction " +
                        "does not contain data transfer.  Fetching level ${fetchSpec.originalLevel}, transaction ID $transactionID"
            )
        payloadChunks.add(transferOrderDataEncChunk)
        logger.debug("Download transfer phase of ${transactionID}: bank acknowledges $x")
    }

    val respPayload = decryptAndDecompressResponse(subscriberDetails, encryptionInfo, payloadChunks)

    // Acknowledgement phase
    val ackRequest = createEbicsRequestForDownloadReceipt(
        subscriberDetails,
        transactionID,
        fetchSpec.isEbics3
    )
    val ackResponseStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        ackRequest
    )
    val ackResponse = parseAndValidateEbicsResponse(
        subscriberDetails,
        ackResponseStr,
        withEbics3 = fetchSpec.isEbics3
    )
    when (ackResponse.technicalReturnCode) {
        EbicsReturnCode.EBICS_DOWNLOAD_POSTPROCESS_DONE -> {
        }
        else -> {
            throw NexusError(
                HttpStatusCode.InternalServerError,
                "Unexpected EBICS return code" +
                        " at acknowledgement phase: ${ackResponse.technicalReturnCode.name}." +
                        "  Fetching level ${fetchSpec.originalLevel}, transaction ID $transactionID"
            )
        }
    }
    logger.debug("Bank acknowledges EBICS download receipt.  Transaction ID: $transactionID.")
    return EbicsDownloadSuccessResult(respPayload, transactionID)
}

// Currently only 1-segment requests.
suspend fun doEbicsUploadTransaction(
    client: HttpClient,
    subscriberDetails: EbicsClientSubscriberDetails,
    uploadSpec: EbicsUploadSpec,
    payload: ByteArray
) {
    if (subscriberDetails.bankEncPub == null) {
        throw NexusError(HttpStatusCode.BadRequest,
            "bank encryption key unknown, request HPB first"
        )
    }
    val preparedUploadData = prepareUploadPayload(
        subscriberDetails,
        payload,
        isEbics3 = uploadSpec.isEbics3
    )
    val req: String = if (uploadSpec.isEbics3) {
        if (uploadSpec.ebics3Service == null)
            throw internalServerError("EBICS 3 service data was expected, but null was found.")
        createEbicsRequestForUploadInitialization(
            subscriberDetails,
            uploadSpec.ebics3Service,
            uploadSpec.orderParams,
            preparedUploadData
        )
    } else {
        if (uploadSpec.orderType == null)
            throw internalServerError("EBICS 2.5 order type was expected, but null was found.")
        createEbicsRequestForUploadInitialization(
            subscriberDetails,
            uploadSpec.orderType,
            uploadSpec.orderParams ?: EbicsStandardOrderParams(),
            preparedUploadData
        )
    }
    logger.debug("EBICS upload message to: ${subscriberDetails.ebicsUrl}")
    val responseStr = client.postToBank(subscriberDetails.ebicsUrl, req)

    val initResponse = parseAndValidateEbicsResponse(
        subscriberDetails,
        responseStr,
        withEbics3 = uploadSpec.isEbics3
    )
    // The bank indicated one error, hence Nexus sent invalid data.
    if (initResponse.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        throw NexusError(
            HttpStatusCode.InternalServerError,
            reason = "EBICS-technical error at init phase:" +
                    " ${initResponse.technicalReturnCode} ${initResponse.reportText}"
        )
    }
    // The bank did NOT indicate any error, but the response
    // lacks required information, blame the bank.
    val transactionID = initResponse.transactionID
    if (initResponse.bankReturnCode != EbicsReturnCode.EBICS_OK) {
        throw NexusError(
            HttpStatusCode.InternalServerError,
            reason = "Bank-technical error at init phase:" +
                    " ${initResponse.bankReturnCode}"
        )
    }
    logger.debug("Bank acknowledges EBICS upload initialization. " +
            " Transaction ID: $transactionID.")

    /* now send actual payload */
    val ebicsPayload = createEbicsRequestForUploadTransferPhase(
        subscriberDetails,
        transactionID,
        preparedUploadData,
        0,
        withEbics3 = uploadSpec.isEbics3
    )
    val txRespStr = client.postToBank(
        subscriberDetails.ebicsUrl,
        ebicsPayload
    )
    val txResp = parseAndValidateEbicsResponse(
        subscriberDetails,
        txRespStr,
        withEbics3 = uploadSpec.isEbics3
    )
    when (txResp.technicalReturnCode) {
        EbicsReturnCode.EBICS_OK -> {/* do nothing */}
        else -> {
            // EBICS failed, blame Nexus.
            throw EbicsProtocolError(
                httpStatusCode = HttpStatusCode.InternalServerError,
                reason = txResp.reportText,
                ebicsTechnicalCode = txResp.technicalReturnCode
            )
        }
    }
    when (txResp.bankReturnCode) {
        EbicsReturnCode.EBICS_OK -> {/* do nothing */}
        else -> {
            /**
             * Although EBICS went fine, the bank complained about
             * the communication content.
             */
            throw EbicsProtocolError(
                httpStatusCode = HttpStatusCode.UnprocessableEntity,
                reason = "bank-technical error: ${txResp.reportText}"
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