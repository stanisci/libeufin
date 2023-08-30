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
 * Handlers for EBICS-related endpoints offered by the nexus for EBICS
 * connections.
 */
package tech.libeufin.nexus.ebics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Paragraph
import io.ktor.server.application.call
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.getLastMessagesTimes
import tech.libeufin.nexus.iso20022.NexusPaymentInitiationData
import tech.libeufin.nexus.iso20022.createPain001document
import tech.libeufin.nexus.logger
import tech.libeufin.nexus.server.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import tech.libeufin.util.ebics_h005.Ebics3Request
import java.io.ByteArrayOutputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import javax.crypto.EncryptedPrivateKeyInfo


/**
 * This type maps the abstract fetch specifications -- as for example
 * they were given via the Nexus JSON API -- to the specific EBICS type.
 */
data class EbicsFetchSpec(
    val orderType: String? = null, // unused for 3.0
    val orderParams: EbicsOrderParams,
    val ebics3Service: Ebics3Request.OrderDetails.Service? = null, // unused for 2.5
    // Not always available, for example at raw POST /download/${ebicsMessageName} calls.
    // It helps to trace back the original level.
    val originalLevel: FetchLevel? = null,
    val isEbics3: Boolean = false
)

/**
 * Collects EBICS 2.5 and/or 3.0 parameters for a unified
 * way of passing parameters.  Individual helpers will then
 * act according to the EBICS version.
 */
data class EbicsUploadSpec(
    val isEbics3: Boolean = false,
    val ebics3Service: Ebics3Request.OrderDetails.Service? = null, // unused for 2.5
    val orderType: String? = null,
    val orderParams: EbicsOrderParams? = null
)

// Validate and store the received document for later ingestion.
private fun validateAndStoreCamt(
    bankConnectionId: String,
    camt: String,
    fetchLevel: FetchLevel,
    transactionID: String? = null, // the EBICS transaction that carried this camt.
    validateBankContent: Boolean = false
) {
    val camtDoc = try {
        XMLUtil.parseStringIntoDom(camt)
    }
    catch (e: Exception) {
        throw badGateway("Could not parse camt document from EBICS transaction $transactionID")
    }
    if (validateBankContent && !XMLUtil.validateFromDom(camtDoc)) {
        logger.error("This document didn't validate: $camt")
        throw badGateway("Camt document from EBICS transaction $transactionID is invalid")
    }

    val msgId = camtDoc.pickStringWithRootNs("/*[1]/*[1]/root:GrpHdr/root:MsgId")
    logger.info("Camt document '$msgId' received via $fetchLevel.")
    transaction {
        val conn = NexusBankConnectionEntity.findByName(bankConnectionId)
        if (conn == null) {
            throw NexusError(
                HttpStatusCode.InternalServerError,
                "bank connection missing"
            )
        }
        val oldMsg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgId }.firstOrNull()
        if (oldMsg == null) {
            NexusBankMessageEntity.new {
                this.bankConnection = conn
                this.fetchLevel = fetchLevel
                this.messageId = msgId
                this.message = ExposedBlob(camt.toByteArray(Charsets.UTF_8))
            }
        }
    }
}

private fun handleEbicsDownloadResult(
    bankResponse: EbicsDownloadResult,
    bankConnectionId: String,
    fetchLevel: FetchLevel
) {
    when (bankResponse) {
        is EbicsDownloadSuccessResult -> {
            bankResponse.orderData.unzipWithLambda {
                // logger.debug("Camt entry (filename (in the Zip archive): ${it.first}): ${it.second}")
                validateAndStoreCamt(
                    bankConnectionId,
                    it.second,
                    fetchLevel,
                    transactionID = bankResponse.transactionID
                )
            }
        }
        is EbicsDownloadBankErrorResult -> {
            throw NexusError(
                HttpStatusCode.BadGateway,
                bankResponse.returnCode.errorCode
            )
        }
        is EbicsDownloadEmptyResult -> {
            // no-op
        }
    }
}

// Fetch EBICS transactions according to the specifications
// (fetchSpec) it finds in the parameters.
private suspend fun fetchEbicsTransactions(
    fetchSpec: EbicsFetchSpec,
    client: HttpClient,
    bankConnectionId: String,
    subscriberDetails: EbicsClientSubscriberDetails,
) {
    /**
     * In this case Nexus will not be able to associate the future
     * EBICS response with the fetch level originally requested by
     * the caller, and therefore refuses to continue the execution.
     * This condition is however in some cases allowed: for example
     * along the "POST /download/$ebicsMessageType" call, where the result
     * is not supposed to be stored in the database and therefore doesn't
     * need its original level.
     */
    if (fetchSpec.originalLevel == null) {
        throw internalServerError(
            "Original fetch level missing, won't download from EBICS"
        )
    }
    val response: EbicsDownloadResult = try {
        doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            fetchSpec
        )
    } catch (e: EbicsProtocolError) {
        /**
         * Although given a error type, an empty transactions list does
         * not mean anything wrong.
         */
        if (e.ebicsTechnicalCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE) {
            logger.debug("EBICS had no new data")
            return
        }
        // re-throw in any other error case.
        throw e
    }
    handleEbicsDownloadResult(
        response,
        bankConnectionId,
        fetchSpec.originalLevel
    )
}

/**
 * Prepares key material and other EBICS details and
 * returns them along a convenient object.
 */
private fun getEbicsSubscriberDetailsInternal(subscriber: EbicsSubscriberEntity): EbicsClientSubscriberDetails {
    var bankAuthPubValue: RSAPublicKey? = null
    if (subscriber.bankAuthenticationPublicKey != null) {
        bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankAuthenticationPublicKey?.bytes!!
        )
    }
    var bankEncPubValue: RSAPublicKey? = null
    if (subscriber.bankEncryptionPublicKey != null) {
        bankEncPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankEncryptionPublicKey?.bytes!!
        )
    }
    return EbicsClientSubscriberDetails(
        bankAuthPub = bankAuthPubValue,
        bankEncPub = bankEncPubValue,

        ebicsUrl = subscriber.ebicsURL,
        hostId = subscriber.hostID,
        userId = subscriber.userID,
        partnerId = subscriber.partnerID,

        customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.bytes),
        customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.bytes),
        customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.encryptionPrivateKey.bytes),
        ebicsIniState = subscriber.ebicsIniState,
        ebicsHiaState = subscriber.ebicsHiaState
    )
}
private fun getSubscriberFromConnection(connectionEntity: NexusBankConnectionEntity): EbicsSubscriberEntity =
    transaction {
        EbicsSubscriberEntity.find {
            NexusEbicsSubscribersTable.nexusBankConnection eq connectionEntity.id
        }.firstOrNull() ?: throw internalServerError("ebics bank connection '${connectionEntity.connectionId}' has no subscriber.")
    }
/**
 * Retrieve Ebics subscriber details given a bank connection.
 */
fun getEbicsSubscriberDetails(bankConnectionId: String): EbicsClientSubscriberDetails {
    val transport = getBankConnection(bankConnectionId)
    val subscriber = getSubscriberFromConnection(transport)

    // transport exists and belongs to caller.
    val ret = getEbicsSubscriberDetailsInternal(subscriber)
    if (transport.dialect != null)
        ret.dialect = transport.dialect
    return ret
}

fun Route.ebicsBankProtocolRoutes(client: HttpClient) {
    post("test-host") {
        val r = call.receive<EbicsHostTestRequest>()
        val qr = doEbicsHostVersionQuery(client, r.ebicsBaseUrl, r.ebicsHostId)
        call.respond(qr)
        return@post
    }
}

fun Route.ebicsBankConnectionRoutes(client: HttpClient) {
    post("/send-ini") {
        requireSuperuser(call.request)
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(
                    HttpStatusCode.BadRequest,
                    "bank connection is not of type 'ebics' (but '${conn.type}')"
                )
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsIniRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hia") {
        requireSuperuser(call.request)
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsHiaRequest(client, subscriber)
        call.respond(resp)
    }

    post("/send-hev") {
        requireSuperuser(call.request)
        val subscriber = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val resp = doEbicsHostVersionQuery(client, subscriber.ebicsUrl, subscriber.hostId)
        call.respond(resp)
    }

    post("/send-hpb") {
        requireSuperuser(call.request)
        val subscriberDetails = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val hpbData = doEbicsHpbRequest(client, subscriberDetails)
        transaction {
            val conn = requireBankConnection(call, "connid")
            val subscriber =
                EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
            subscriber.bankAuthenticationPublicKey = ExposedBlob((hpbData.authenticationPubKey.encoded))
            subscriber.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
        }
        call.respond(object {})
    }

    // Directly import accounts.  Used for testing.
    post("/import-accounts") {
        requireSuperuser(call.request)
        val subscriberDetails = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val response = doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            EbicsFetchSpec(
                orderType = "HTD",
                orderParams = EbicsStandardOrderParams()
            )
        )
        when (response) {
            is EbicsDownloadEmptyResult -> {
                // no-op
                logger.warn("HTD response was empty.")
            }
            is EbicsDownloadBankErrorResult -> {
                throw NexusError(
                    HttpStatusCode.BadGateway,
                    response.returnCode.errorCode
                )
            }
            is EbicsDownloadSuccessResult -> {
                val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                    response.orderData.toString(Charsets.UTF_8)
                )
                transaction {
                    val conn = requireBankConnection(call, "connid")
                    payload.value.partnerInfo.accountInfoList?.forEach {
                        NexusBankAccountEntity.new {
                            bankAccountName = it.id
                            accountHolder = it.accountHolder ?: "NOT-GIVEN"
                            iban = it.accountNumberList?.filterIsInstance<EbicsTypes.GeneralAccountNumber>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                            bankCode = it.bankCodeList?.filterIsInstance<EbicsTypes.GeneralBankCode>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(
                                    HttpStatusCode.NotFound,
                                    reason = "bank gave no BIC"
                                )
                            defaultBankConnection = conn
                            highestSeenBankMessageSerialId = 0
                        }
                    }
                }
                response.orderData.toString(Charsets.UTF_8)
            }
        }
        call.respond(object {})
    }

    post("/download/{msgtype}") {
        requireSuperuser(call.request)
        val orderType = requireNotNull(call.parameters["msgtype"]).uppercase(Locale.ROOT)
        if (orderType.length != 3) {
            throw NexusError(HttpStatusCode.BadRequest, "ebics order type must be three characters")
        }
        val paramsJson = call.receiveNullable<EbicsStandardOrderParamsEmptyJson>()
        val orderParams = paramsJson?.toOrderParams() ?: EbicsStandardOrderParams()
        val subscriberDetails = transaction {
            val conn = requireBankConnection(call, "connid")
            if (conn.type != "ebics") {
                throw NexusError(HttpStatusCode.BadRequest, "bank connection is not of type 'ebics'")
            }
            getEbicsSubscriberDetails(conn.connectionId)
        }
        val response = doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            EbicsFetchSpec(
                orderType = orderType,
                orderParams = orderParams,
                ebics3Service = null,
                originalLevel = null
            )
        )
        when (response) {
            is EbicsDownloadEmptyResult -> {
                logger.info(orderType + " response was empty.") // no op
            }
            is EbicsDownloadSuccessResult -> {
                call.respondText(
                    response.orderData.toString(Charsets.UTF_8),
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            }
            is EbicsDownloadBankErrorResult -> {
                call.respond(
                    HttpStatusCode.BadGateway,
                    NexusErrorJson(
                        error = NexusErrorDetailJson(
                            type = "bank-error",
                            description = response.returnCode.errorCode
                        )
                    )
                )
            }
        }
    }
}

/**
 * Do the Hpb request when we don't know whether our keys have been submitted or not.
 *
 * Return true when the tentative HPB request succeeded, and thus key initialization is done.
 */
private suspend fun tentativeHpb(client: HttpClient, connId: String): Boolean {
    val subscriber = transaction { getEbicsSubscriberDetails(connId) }
    val hpbData = try {
        doEbicsHpbRequest(client, subscriber)
    } catch (e: EbicsProtocolError) {
        logger.info("failed tentative hpb request", e)
        return false
    }
    transaction {
        val conn = NexusBankConnectionEntity.findByName(connId)
        if (conn == null) {
            throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
        }
        val subscriberEntity =
            EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
        subscriberEntity.ebicsIniState = EbicsInitState.SENT
        subscriberEntity.ebicsHiaState = EbicsInitState.SENT
        subscriberEntity.bankAuthenticationPublicKey =
            ExposedBlob((hpbData.authenticationPubKey.encoded))
        subscriberEntity.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
    }
    return true
}

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

// A null return value indicates that the connection uses EBICS 3.0
private fun getSubmissionTypeAfterDialect(dialect: String? = null): String? {
    return when (dialect) {
        "pf" -> null // "XE2"
        else -> "CCT"
    }
}

private fun getStatementSpecAfterDialect(dialect: String? = null, p: EbicsOrderParams): EbicsFetchSpec {
    return when (dialect) {
        "pf" -> EbicsFetchSpec(
            orderType = "Z53",
            orderParams = p,
            ebics3Service = Ebics3Request.OrderDetails.Service().apply {
                serviceName = "EOP"
                messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                    value = "camt.053"
                    version = "04"
                }
                scope = "CH"
                container = Ebics3Request.OrderDetails.Service.Container().apply {
                    containerType = "ZIP"
                }
            },
            originalLevel = FetchLevel.STATEMENT
        )
        else -> EbicsFetchSpec(
            orderType = "C53",
            orderParams = p,
            ebics3Service = null,
            originalLevel = FetchLevel.STATEMENT
        )
    }
}

private fun getNotificationSpecAfterDialect(dialect: String? = null, p: EbicsOrderParams): EbicsFetchSpec {
    return when (dialect) {
        "pf" -> EbicsFetchSpec(
            orderType = null, // triggers 3.0
            orderParams = p,
            ebics3Service = Ebics3Request.OrderDetails.Service().apply {
                serviceName = "REP"
                messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                    value = "camt.054"
                    version = "08"
                }
                scope = "CH"
                container = Ebics3Request.OrderDetails.Service.Container().apply {
                    containerType = "ZIP"
                }
            },
            originalLevel = FetchLevel.NOTIFICATION,
            isEbics3 = true
        )
        else -> EbicsFetchSpec(
            orderType = "C54",
            orderParams = p,
            ebics3Service = null,
            originalLevel = FetchLevel.NOTIFICATION
        )
    }
}
private fun getReportSpecAfterDialect(dialect: String? = null, p: EbicsOrderParams): EbicsFetchSpec {
    return when (dialect) {
        "pf" -> EbicsFetchSpec(
            orderType = "Z52",
            orderParams = p,
            ebics3Service = null,
            originalLevel = FetchLevel.REPORT
        )
        else -> EbicsFetchSpec(
            orderType = "C52",
            orderParams = p,
            ebics3Service = null,
            originalLevel = FetchLevel.REPORT
        )
    }
}

/**
 * This function returns a possibly empty list of Exception.
 * That helps not to stop fetching if ONE operation fails.  Notably,
 * C52 and C53 may be asked along one invocation of this function,
 * therefore storing the exception on C52 allows the C53 to still
 * take place.  The caller then decides how to handle the exceptions.
 */
class EbicsBankConnectionProtocol: BankConnectionProtocol {
    /**
     * Downloads the pain.002 that informs about previous
     * payments submissions.  Not all the banks offer this
     * service; some may use analog channels.
     */
    suspend fun fetchPaymentReceipt(
        fetchSpec: FetchSpecJson,
        client: HttpClient,
        bankConnectionId: String,
        accountId: String
    ) {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(bankConnectionId) }
        // Typically a date range.
        if (fetchSpec.level != FetchLevel.RECEIPT) {
            logger.error("This method accepts only RECEIPT as the fetch level, not '${fetchSpec.level}'.")
            throw badRequest("Invalid params to get payments receipts: use fetch level RECEIPT.")
        }
        val ebicsOrderInfo = when(fetchSpec) {
            is FetchSpecLatestJson -> {
                EbicsFetchSpec(
                    orderType = "Z01", // PoFi specific.
                    orderParams = EbicsStandardOrderParams(),
                    originalLevel = fetchSpec.level
                )
            }
            else -> throw NotImplementedError("Fetch spec '${fetchSpec::class}' not supported for payment receipts.")
        }
        // Proceeding to download now.
        val response = try {
            doEbicsDownloadTransaction(
                client,
                subscriberDetails,
                EbicsFetchSpec(
                    orderType = ebicsOrderInfo.orderType,
                    orderParams = ebicsOrderInfo.orderParams
                )
            )
        } catch (e: EbicsProtocolError) {
            if (e.ebicsTechnicalCode == EbicsReturnCode.EBICS_NO_DOWNLOAD_DATA_AVAILABLE) {
                logger.debug("EBICS had no new data")
                return
            }
            // re-throw in any other error case.
            throw e
        }
        when(response) {
            is EbicsDownloadEmptyResult -> {
                // no-op
            }
            is EbicsDownloadBankErrorResult -> {
                logger.error("Bank technical code: ${response.returnCode}")
            }
            is EbicsDownloadSuccessResult -> {
                // Extracting the content (pain.002) and parsing it.
                val orderData: String = response.orderData.toString()
                logger.debug(orderData)
                val doc = XMLUtil.parseStringIntoDom(orderData)

            }
        }
    }

    override fun getBankUrl(connId: String): String {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(connId) }
        return subscriberDetails.ebicsUrl
    }
    override suspend fun fetchTransactions(
        fetchSpec: FetchSpecJson,
        client: HttpClient,
        bankConnectionId: String,
        accountId: String
    ): List<Exception>? {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(bankConnectionId) }
        val lastTimes = getLastMessagesTimes(accountId)
        /**
         * Will be filled with fetch instructions, according
         * to the parameters received from the client.
         */
        val specs = mutableListOf<EbicsFetchSpec>()
        /**
         * 'level' indicates whether to fetch statements and/or reports,
         * whereas 'p' usually carries a date range.
         */
        fun addForLevel(l: FetchLevel, p: EbicsOrderParams) {
            when (l) {
                FetchLevel.ALL -> {
                    specs.add(getReportSpecAfterDialect(subscriberDetails.dialect, p))
                    specs.add(getStatementSpecAfterDialect(subscriberDetails.dialect, p))
                }
                FetchLevel.REPORT -> {
                    specs.add(getReportSpecAfterDialect(subscriberDetails.dialect, p))
                }
                FetchLevel.STATEMENT -> {
                    specs.add(getStatementSpecAfterDialect(subscriberDetails.dialect, p))
                }
                FetchLevel.NOTIFICATION -> {
                    specs.add(getNotificationSpecAfterDialect(subscriberDetails.dialect, p))
                }
                else -> {
                    logger.error("fetch level wrong in addForLevel() helper: ${fetchSpec.level}.")
                    throw badRequest("Fetch level ${fetchSpec.level} not supported")
                }
            }
        }
        // Figuring out what time range to put in the fetch instructions.
        when (fetchSpec) {
            is FetchSpecTimeRangeJson -> {
                // the parse() method defaults to the YYYY-MM-DD format.
                // If parsing fails, the global catcher intervenes.

                val start: LocalDate = parseDashedDate(fetchSpec.start)
                val end: LocalDate = parseDashedDate(fetchSpec.end)
                val p = EbicsStandardOrderParams(
                    EbicsDateRange(
                        start = start.atStartOfDay().atZone(ZoneId.systemDefault()),
                        end = end.atStartOfDay().atZone(ZoneId.systemDefault())
                    )
                )
                addForLevel(fetchSpec.level, p)
            }
            is FetchSpecLatestJson -> {
                val p = EbicsStandardOrderParams()
                addForLevel(fetchSpec.level, p)
            }
            is FetchSpecAllJson -> {
                val start = ZonedDateTime.ofInstant(
                    Instant.EPOCH,
                    ZoneOffset.UTC
                )
                val end = ZonedDateTime.ofInstant(Instant.now(), ZoneOffset.systemDefault())
                val p = EbicsStandardOrderParams(
                    EbicsDateRange(start, end)
                )
                addForLevel(fetchSpec.level, p)
            }
            /**
             * This branch differentiates the last date of reports and
             * statements and builds the fetch instructions for each of
             * them.  For this reason, it does not use the "addForLevel()"
             * helper, since that uses the same date for all the messages
             * falling in the ALL level.
             */
            is FetchSpecSinceLastJson -> {
                val pRep = EbicsStandardOrderParams(
                    EbicsDateRange(
                        lastTimes.lastReport ?: ZonedDateTime.ofInstant(
                            Instant.EPOCH,
                            ZoneOffset.UTC
                        ), ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                val pStmt = EbicsStandardOrderParams(
                    EbicsDateRange(
                        lastTimes.lastStatement ?: ZonedDateTime.ofInstant(
                            Instant.EPOCH,
                            ZoneOffset.UTC
                        ), ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                val pNtfn = EbicsStandardOrderParams(
                    EbicsDateRange(
                        lastTimes.lastNotification ?: ZonedDateTime.ofInstant(
                            Instant.EPOCH,
                            ZoneOffset.UTC
                        ), ZonedDateTime.now(ZoneOffset.UTC)
                    )
                )
                when (fetchSpec.level) {
                    FetchLevel.ALL -> {
                        specs.add(getReportSpecAfterDialect(subscriberDetails.dialect, pRep))
                        specs.add(getStatementSpecAfterDialect(subscriberDetails.dialect, pRep))
                    }
                    FetchLevel.REPORT -> {
                        specs.add(getReportSpecAfterDialect(subscriberDetails.dialect, pRep))
                    }
                    FetchLevel.STATEMENT -> {
                        specs.add(getStatementSpecAfterDialect(subscriberDetails.dialect, pStmt))
                    }
                    FetchLevel.NOTIFICATION -> {
                        specs.add(getNotificationSpecAfterDialect(subscriberDetails.dialect, pNtfn))
                    }
                    else -> throw badRequest("Fetch level ${fetchSpec.level} " +
                            "not supported in the 'since last' EBICS time range.")
                }
            }
        }
        // Downloads and stores the bank message into the database.  No ingestion.
        val errors = mutableListOf<Exception>()
        for (spec in specs) {
            try {
                fetchEbicsTransactions(
                    spec,
                    client,
                    bankConnectionId,
                    subscriberDetails
                )
            } catch (e: Exception) {
                logger.warn("Fetching transactions (${spec.originalLevel}) excepted: ${e.message}.")
                e.printStackTrace()
                errors.add(e)
            }
        }
        if (errors.size > 0)
            return errors
        return null
    }

    // Submit one Pain.001 for one payment initiations.
    override suspend fun submitPaymentInitiation(
        httpClient: HttpClient,
        paymentInitiationId: Long
    ) {
        val dbData = transaction {
            val preparedPayment = getPaymentInitiation(paymentInitiationId)
            val conn = preparedPayment.bankAccount.defaultBankConnection ?: throw NexusError(
                HttpStatusCode.NotFound,
                "no default bank connection available for submission"
            )
            val subscriberDetails = getEbicsSubscriberDetails(conn.connectionId)
            val painMessage = createPain001document(
                NexusPaymentInitiationData(
                    debtorIban = preparedPayment.bankAccount.iban,
                    debtorBic = preparedPayment.bankAccount.bankCode,
                    debtorName = preparedPayment.bankAccount.accountHolder,
                    currency = preparedPayment.currency,
                    amount = preparedPayment.sum,
                    creditorIban = preparedPayment.creditorIban,
                    creditorName = preparedPayment.creditorName,
                    creditorBic = preparedPayment.creditorBic,
                    paymentInformationId = preparedPayment.paymentInformationId,
                    preparationTimestamp = preparedPayment.preparationDate,
                    subject = preparedPayment.subject,
                    instructionId = preparedPayment.instructionId,
                    endToEndId = preparedPayment.endToEndId,
                    messageId = preparedPayment.messageId
                ),
                dialect = subscriberDetails.dialect
            )
            object {
                val painXml = painMessage
                val subscriberDetails = subscriberDetails
            }
        }
        val isPoFi = dbData.subscriberDetails.dialect == "pf"
        val uploadSpec = EbicsUploadSpec(
            isEbics3 = isPoFi,
            orderType = if (!isPoFi) getSubmissionTypeAfterDialect(dbData.subscriberDetails.dialect) else null,
            orderParams = EbicsStandardOrderParams(),
            ebics3Service = if (isPoFi)
                Ebics3Request.OrderDetails.Service().apply {
                    serviceName = "MCT"
                    scope = "CH"
                    messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                        value = "pain.001"
                        version = "09"
                    }
                }
                else null
        )
        doEbicsUploadTransaction(
            httpClient,
            dbData.subscriberDetails,
            uploadSpec,
            dbData.painXml.toByteArray(Charsets.UTF_8)
        )
        transaction {
            val payment = getPaymentInitiation(paymentInitiationId)
            payment.submitted = true
            payment.submissionDate = LocalDateTime.now().millis()
        }
    }

    override fun exportAnalogDetails(conn: NexusBankConnectionEntity): ByteArray {
        val ebicsSubscriber = transaction { getEbicsSubscriberDetails(conn.connectionId) }
        val po = ByteArrayOutputStream()
        val pdfWriter = PdfWriter(po)
        val pdfDoc = PdfDocument(pdfWriter)
        val date = LocalDateTime.now()
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

        fun writeCommon(doc: Document) {
            doc.add(
                Paragraph(
                    """
            Datum: $dateStr
            Teilnehmer: ${conn.id.value}
            Host-ID: ${ebicsSubscriber.hostId}
            User-ID: ${ebicsSubscriber.userId}
            Partner-ID: ${ebicsSubscriber.partnerId}
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
            writeKey(it, ebicsSubscriber.customerSignPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
            it.add(AreaBreak())

            it.add(Paragraph("Authentifikationsschlüssel").setFontSize(24f))
            writeCommon(it)
            it.add(Paragraph("Öffentlicher Schlüssel (Public key for the identification and authentication signature)"))
            writeKey(it, ebicsSubscriber.customerAuthPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
            it.add(AreaBreak())

            it.add(Paragraph("Verschlüsselungsschlüssel").setFontSize(24f))
            writeCommon(it)
            it.add(Paragraph("Öffentlicher Schlüssel (Public encryption key)"))
            writeKey(it, ebicsSubscriber.customerEncPriv)
            it.add(Paragraph("\n"))
            writeSigLine(it)
        }
        pdfWriter.flush()
        return po.toByteArray()
    }

    override fun exportBackup(bankConnectionId: String, passphrase: String): JsonNode {
        val subscriber = transaction { getEbicsSubscriberDetails(bankConnectionId) }
        val ret = EbicsKeysBackupJson(
            type = "ebics",
            dialect = subscriber.dialect,
            userID = subscriber.userId,
            hostID = subscriber.hostId,
            partnerID = subscriber.partnerId,
            ebicsURL = subscriber.ebicsUrl,
            authBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerAuthPriv.encoded,
                    passphrase
                )
            ),
            encBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerEncPriv.encoded,
                    passphrase
                )
            ),
            sigBlob = bytesToBase64(
                CryptoUtil.encryptKey(
                    subscriber.customerSignPriv.encoded,
                    passphrase
                )
            ),
            bankAuthBlob = run {
                val maybeBankAuthPub = subscriber.bankAuthPub
                if (maybeBankAuthPub != null)
                    return@run bytesToBase64(maybeBankAuthPub.encoded)
                null
            },
            bankEncBlob = run {
                val maybeBankEncPub = subscriber.bankEncPub
                if (maybeBankEncPub != null)
                    return@run bytesToBase64(maybeBankEncPub.encoded)
                null
            }
        )
        val mapper = ObjectMapper()
        return mapper.valueToTree(ret)
    }

    override fun getConnectionDetails(conn: NexusBankConnectionEntity): JsonNode {
        val ebicsSubscriber = transaction { getEbicsSubscriberDetails(conn.connectionId) }
        val mapper = ObjectMapper()
        val details = mapper.createObjectNode()
        details.put("ebicsUrl", ebicsSubscriber.ebicsUrl)
        details.put("ebicsHostId", ebicsSubscriber.hostId)
        details.put("partnerId", ebicsSubscriber.partnerId)
        details.put("userId", ebicsSubscriber.userId)
        details.put(
            "customerAuthKeyHash",
            CryptoUtil.getEbicsPublicKeyHash(
                CryptoUtil.getRsaPublicFromPrivate(ebicsSubscriber.customerAuthPriv)
            ).toHexString()
        )
        details.put(
            "customerEncKeyHash",
            CryptoUtil.getEbicsPublicKeyHash(
                CryptoUtil.getRsaPublicFromPrivate(ebicsSubscriber.customerEncPriv)
            ).toHexString()
        )
        val bankAuthPubImmutable = ebicsSubscriber.bankAuthPub
        if (bankAuthPubImmutable != null) {
            details.put(
                "bankAuthKeyHash",
                CryptoUtil.getEbicsPublicKeyHash(bankAuthPubImmutable).toHexString()
            )
        }
        val bankEncPubImmutable = ebicsSubscriber.bankEncPub
        if (bankEncPubImmutable != null) {
            details.put(
                "bankEncKeyHash",
                CryptoUtil.getEbicsPublicKeyHash(bankEncPubImmutable).toHexString()
            )
        }
        val node = mapper.createObjectNode()
        node.put("type", conn.type)
        node.put("owner", conn.owner.username)
        node.put("ready", true) // test with #6715 needed.
        node.set<JsonNode>("details", details)
        return node
    }
    override fun createConnection(
        connId: String,
        user: NexusUserEntity,
        data: JsonNode
    ) {
        val newTransportData = jacksonObjectMapper()
            .treeToValue(data, EbicsNewTransport::class.java) ?: throw NexusError(
            HttpStatusCode.BadRequest,
            "Ebics details not found in request"
        )
        val bankConn = NexusBankConnectionEntity.new {
            this.connectionId = connId
            owner = user
            type = "ebics"
            this.dialect = newTransportData.dialect
        }
        val pairA = CryptoUtil.generateRsaKeyPair(2048)
        val pairB = CryptoUtil.generateRsaKeyPair(2048)
        val pairC = CryptoUtil.generateRsaKeyPair(2048)
        EbicsSubscriberEntity.new {
            ebicsURL = newTransportData.ebicsURL
            hostID = newTransportData.hostID
            partnerID = newTransportData.partnerID
            userID = newTransportData.userID
            systemID = newTransportData.systemID
            signaturePrivateKey = ExposedBlob((pairA.private.encoded))
            encryptionPrivateKey = ExposedBlob((pairB.private.encoded))
            authenticationPrivateKey = ExposedBlob((pairC.private.encoded))
            nexusBankConnection = bankConn
            ebicsIniState = EbicsInitState.NOT_SENT
            ebicsHiaState = EbicsInitState.NOT_SENT
        }
    }

    override fun createConnectionFromBackup(
        connId: String,
        user: NexusUserEntity,
        passphrase: String?,
        backup: JsonNode
    ) {
        if (passphrase === null) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "EBICS backup needs passphrase"
            )
        }
        val ebicsBackup = jacksonObjectMapper().treeToValue(backup, EbicsKeysBackupJson::class.java)
        val bankConn = NexusBankConnectionEntity.new {
            connectionId = connId
            owner = user
            type = "ebics"
            this.dialect = ebicsBackup.dialect
        }
        val (authKey, encKey, sigKey) = try {
            Triple(
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.authBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.encBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.sigBlob)),
                    passphrase
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            logger.info("Restoring keys failed, probably due to wrong passphrase")
            throw NexusError(
                HttpStatusCode.BadRequest,
                "Bad backup given"
            )
        }
        try {
            EbicsSubscriberEntity.new {
                ebicsURL = ebicsBackup.ebicsURL
                hostID = ebicsBackup.hostID
                partnerID = ebicsBackup.partnerID
                userID = ebicsBackup.userID
                signaturePrivateKey = ExposedBlob(sigKey.encoded)
                encryptionPrivateKey = ExposedBlob((encKey.encoded))
                authenticationPrivateKey = ExposedBlob((authKey.encoded))
                nexusBankConnection = bankConn
                ebicsIniState = EbicsInitState.UNKNOWN
                ebicsHiaState = EbicsInitState.UNKNOWN
                if (ebicsBackup.bankAuthBlob != null) {
                    val keyBlob = base64ToBytes(ebicsBackup.bankAuthBlob)
                    try { CryptoUtil.loadRsaPublicKey(keyBlob) }
                    catch (e: Exception) {
                        logger.error("Could not restore bank's auth public key")
                        throw NexusError(
                            HttpStatusCode.BadRequest,
                            "Bad bank's auth pub"
                        )
                    }
                    bankAuthenticationPublicKey = ExposedBlob(keyBlob)
                }
                if (ebicsBackup.bankEncBlob != null) {
                    val keyBlob = base64ToBytes(ebicsBackup.bankEncBlob)
                    try { CryptoUtil.loadRsaPublicKey(keyBlob) }
                    catch (e: Exception) {
                        logger.error("Could not restore bank's enc public key")
                        throw NexusError(
                            HttpStatusCode.BadRequest,
                            "Bad bank's enc pub"
                        )
                    }
                    bankEncryptionPublicKey = ExposedBlob(keyBlob)
                }
             }
        } catch (e: Exception) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "exception: $e"
            )
        }
        return
    }

    override suspend fun fetchAccounts(client: HttpClient, connId: String) {
        val subscriberDetails = transaction { getEbicsSubscriberDetails(connId) }
        val response = doEbicsDownloadTransaction(
            client,
            subscriberDetails,
            EbicsFetchSpec(
                orderType = "HTD",
                orderParams = EbicsStandardOrderParams()
            )
        )
        when (response) {
            is EbicsDownloadEmptyResult -> {
                // no-op
                logger.warn("HTD response was empty.")
            }
            is EbicsDownloadBankErrorResult -> {
                throw NexusError(
                    HttpStatusCode.BadGateway,
                    response.returnCode.errorCode
                )
            }
            is EbicsDownloadSuccessResult -> {
                val payload = XMLUtil.convertStringToJaxb<HTDResponseOrderData>(
                    response.orderData.toString(Charsets.UTF_8)
                )
                transaction {
                    payload.value.partnerInfo.accountInfoList?.forEach { accountInfo ->
                        val conn = NexusBankConnectionEntity.findByName(connId) ?: throw NexusError(
                            HttpStatusCode.NotFound,
                            "bank connection not found"
                        )
                        // Avoiding to store twice one downloaded bank account.
                        val isDuplicate = OfferedBankAccountsTable.select {
                            OfferedBankAccountsTable.bankConnection eq conn.id and (
                                    OfferedBankAccountsTable.offeredAccountId eq accountInfo.id)
                        }.firstOrNull()
                        if (isDuplicate != null) return@forEach
                        // Storing every new bank account.
                        OfferedBankAccountsTable.insert { newRow ->
                            newRow[accountHolder] = accountInfo.accountHolder ?: "NOT GIVEN"
                            newRow[iban] =
                                accountInfo.accountNumberList?.filterIsInstance<EbicsTypes.GeneralAccountNumber>()
                                    ?.find { it.international }?.value
                                    ?: throw NexusError(HttpStatusCode.NotFound, reason = "bank gave no IBAN")
                            newRow[bankCode] = accountInfo.bankCodeList?.filterIsInstance<EbicsTypes.GeneralBankCode>()
                                ?.find { it.international }?.value
                                ?: throw NexusError(
                                    HttpStatusCode.NotFound,
                                    reason = "bank gave no BIC"
                                )
                            newRow[bankConnection] = requireBankConnectionInternal(connId).id
                            newRow[offeredAccountId] = accountInfo.id
                        }
                    }
                }
            }
        }
    }

    override suspend fun connect(client: HttpClient, connId: String) {
        val subscriber = transaction { getEbicsSubscriberDetails(connId) }
        if (subscriber.bankAuthPub != null && subscriber.bankEncPub != null) {
            return
        }
        if (subscriber.ebicsIniState == EbicsInitState.UNKNOWN || subscriber.ebicsHiaState == EbicsInitState.UNKNOWN) {
            if (tentativeHpb(client, connId)) {
                /**
                 * NOTE/FIXME: in case the HIA/INI did succeed (state is UNKNOWN but Sandbox
                 * has somehow the keys), here the state should be set to SENT, because later -
                 * when the Sandbox will respond to the INI/HIA requests - we'll get a
                 * EBICS_INVALID_USER_OR_USER_STATE.  Hence, the state will never switch to
                 * SENT again.
                 */
                return
            }
        }
        val iniDone = when (subscriber.ebicsIniState) {
            EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
                val iniResp = doEbicsIniRequest(client, subscriber)
                iniResp.bankReturnCode == EbicsReturnCode.EBICS_OK && iniResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
            }
            EbicsInitState.SENT -> true
        }
        val hiaDone = when (subscriber.ebicsHiaState) {
            EbicsInitState.NOT_SENT, EbicsInitState.UNKNOWN -> {
                val hiaResp = doEbicsHiaRequest(client, subscriber)
                hiaResp.bankReturnCode == EbicsReturnCode.EBICS_OK && hiaResp.technicalReturnCode == EbicsReturnCode.EBICS_OK
            }
            EbicsInitState.SENT -> true
        }
        val hpbData = try {
            doEbicsHpbRequest(client, subscriber)
        } catch (e: EbicsProtocolError) {
            logger.warn("failed HPB request", e)
            null
        }
        transaction {
            val conn = NexusBankConnectionEntity.findByName(connId)
            if (conn == null) {
                throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
            }
            val subscriberEntity =
                EbicsSubscriberEntity.find { NexusEbicsSubscribersTable.nexusBankConnection eq conn.id }.first()
            if (iniDone) {
                subscriberEntity.ebicsIniState = EbicsInitState.SENT
            }
            if (hiaDone) {
                subscriberEntity.ebicsHiaState = EbicsInitState.SENT
            }
            if (hpbData != null) {
                subscriberEntity.bankAuthenticationPublicKey =
                    ExposedBlob((hpbData.authenticationPubKey.encoded))
                subscriberEntity.bankEncryptionPublicKey = ExposedBlob((hpbData.encryptionPubKey.encoded))
            }
        }
    }
}
