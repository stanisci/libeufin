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
package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.groups.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.types.*
import io.ktor.client.*
import kotlinx.coroutines.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.common.*
import tech.libeufin.ebics.*
import tech.libeufin.ebics.ebics_h005.Ebics3Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.io.path.*
import kotlin.io.*

/**
 * Necessary data to perform a download.
 */
data class FetchContext(
    /**
     * Config handle.
     */
    val cfg: EbicsSetupConfig,
    /**
     * HTTP client handle to reach the bank
     */
    val httpClient: HttpClient,
    /**
     * EBICS subscriber private keys.
     */
    val clientKeys: ClientPrivateKeysFile,
    /**
     * Bank public keys.
     */
    val bankKeys: BankPublicKeysFile,
    /**
     * Start date of the returned documents.  Only
     * used in --transient mode.
     */
    var pinnedStart: Instant?,
    val fileLogger: FileLogger
)

/**
 * Downloads content via EBICS, according to the order params passed
 * by the caller.
 *
 * @param T [Ebics2Request] for EBICS 2 or [Ebics3Request.OrderDetails.BTOrderParams] for EBICS 3
 * @param ctx [FetchContext]
 * @param req contains the instructions for the download, namely
 *            which document is going to be downloaded from the bank.
 * @return the [ByteArray] payload.  On an empty response, the array
 *         length is zero.  It returns null, if the bank assigned an
 *         error to the EBICS transaction.
 */
private suspend inline fun downloadHelper(
    ctx: FetchContext,
    lastExecutionTime: Instant? = null,
    doc: SupportedDocument
): ByteArray {
    val isEbics3 = doc != SupportedDocument.PAIN_002_LOGS
    val initXml = if (isEbics3) {
        createEbics3DownloadInitialization(
            ctx.cfg,
            ctx.bankKeys,
            ctx.clientKeys,
            prepEbics3Document(doc, lastExecutionTime)
        )
    } else {
        val ebics2Req = prepEbics2Document(doc, lastExecutionTime)
        createEbics25DownloadInit(
            ctx.cfg,
            ctx.clientKeys,
            ctx.bankKeys,
            ebics2Req.messageType,
            ebics2Req.orderParams
        )
    }
    logger.trace(initXml)
    try {
        return doEbicsDownload(
            ctx.httpClient,
            ctx.cfg,
            ctx.clientKeys,
            ctx.bankKeys,
            initXml,
            isEbics3,
            tolerateEmptyResult = true
        )
    } catch (e: EbicsSideException) {
        logger.error(e.message)
        /**
         * Failing regardless of the error being at the client or at the
         * bank side.  A client with an unreliable bank is not useful, hence
         * failing here.
         */
        throw e
    }
}

/**
 * Converts the 2-digits fraction value as given by the bank
 * (postfinance dialect), to the Taler 8-digit value (db representation).
 *
 * @param bankFrac fractional value
 * @return the Taler fractional value with at most 8 digits.
 */
private fun makeTalerFrac(bankFrac: String): Int {
    if (bankFrac.length > 2) throw Exception("Fractional value has more than 2 digits")
    var buf = bankFrac.toIntOrNull() ?: throw Exception("Fractional value not an Int: $bankFrac")
    repeat(8 - bankFrac.length) {
        buf *= 10
    }
    return buf
}

/**
 * Converts valid reserve pubs to its binary representation.
 *
 * @param maybeReservePub input.
 * @return [ByteArray] or null if not valid.
 */
fun isReservePub(maybeReservePub: String): ByteArray? {
    if (maybeReservePub.length != 52) {
        logger.error("Not a reserve pub, length (${maybeReservePub.length}) is not 52")
        return null
    }
    val dec = try {
        Base32Crockford.decode(maybeReservePub)
    } catch (e: EncodingException) {
        logger.error("Not a reserve pub: $maybeReservePub")
        return null
    }
    logger.debug("Reserve how many bytes: ${dec.size}")
    // this check would only be effective after #7980
    if (dec.size != 32) {
        logger.error("Not a reserve pub, wrong length: ${dec.size}")
        return null
    }
    return dec
}

/**
 * Extract the part of the subject that might represent a
 * valid Taler reserve public key.  That addresses some bank
 * policies of adding extra information around the payment
 * subject.
 *
 * @param subject raw subject as read from the bank.
 */
fun removeSubjectNoise(subject: String): String? {
    val re = "\\b[a-z0-9A-Z]{52}\\b".toRegex()
    val result = re.find(subject.replace("[\n]+".toRegex(), "")) ?: return null
    return result.value
}

/**
 * Checks the two conditions that may invalidate one incoming
 * payment: subject validity and availability.
 *
 * @param payment incoming payment whose subject is to be checked.
 * @return [ByteArray] as the reserve public key, or null if the
 *         payment cannot lead to a Taler withdrawal.
 */
private suspend fun getTalerReservePub(
    payment: IncomingPayment
): ByteArray? {
    // Removing noise around the potential reserve public key.
    val maybeReservePub = removeSubjectNoise(payment.wireTransferSubject) ?: return null
    // Checking validity first.
    val dec = isReservePub(maybeReservePub) ?: return null
    return dec
}

/**
 * Ingests an outgoing payment. It links it to the initiated
 * outgoing transaction that originated it.
 *
 * @param db database handle.
 * @param payment payment to (maybe) ingest.
 */
suspend fun ingestOutgoingPayment(
    db: Database,
    payment: OutgoingPayment
) {
    val result = db.registerOutgoing(payment)
    if (result.new) {
        if (result.initiated)
            logger.info("$payment")
        else 
            logger.warn("$payment recovered")
    } else {
        logger.debug("OUT '${payment.messageId}' already seen")
    }
}

/**
 * Ingests an incoming payment.  Stores the payment into valid talerable ones
 * or bounces it, according to the subject.
 *
 * @param db database handle.
 * @param currency fiat currency of the watched bank account.
 * @param payment payment to (maybe) ingest.
 */
suspend fun ingestIncomingPayment(
    db: Database,
    payment: IncomingPayment
) {
    val reservePub = getTalerReservePub(payment)
    if (reservePub == null) {
        val result = db.registerMalformedIncoming(
            payment,
            payment.amount, 
            Instant.now()
        )
        if (result.new) {
            logger.info("$payment bounced in '${result.bounceId}'")
        } else {
            logger.debug("IN '${payment.bankId}' already seen and bounced in '${result.bounceId}'")
        }
    } else {
        val result = db.registerTalerableIncoming(payment, reservePub)
        if (result.new) {
            logger.info("$payment")
        } else {
            logger.debug("IN '${payment.bankId}' already seen")
        }
    }
}

private fun ingestDocument(
    db: Database,
    currency: String,
    xml: ByteArray,
    whichDocument: SupportedDocument
) {
    when (whichDocument) {
        SupportedDocument.CAMT_054 -> {
            try {
                val incomingPayments = mutableListOf<IncomingPayment>()
                val outgoingPayments = mutableListOf<OutgoingPayment>()
                parseTxNotif(xml, currency, incomingPayments, outgoingPayments)

                runBlocking {
                    incomingPayments.forEach {
                        ingestIncomingPayment(db, it)
                    }
                    outgoingPayments.forEach {
                        ingestOutgoingPayment(db, it)
                    }
                }
            } catch (e: Exception) {
                throw Exception("Ingesting notifications failed", e)
            }
        }
        SupportedDocument.PAIN_002_LOGS -> {
            val acks = parseCustomerAck(xml)
            for (ack in acks) {
                when (ack.actionType) {
                    HacAction.FILE_DOWNLOAD -> logger.trace("$ack")
                    HacAction.ORDER_HAC_FINAL_POS -> {
                        // TODO update pending transaction status
                        logger.debug("$ack")
                        logger.info("Order '${ack.orderId}' was accepted at ${ack.timestamp.fmtDateTime()}")
                    }
                    HacAction.ORDER_HAC_FINAL_NEG -> {
                        // TODO update pending transaction status
                        logger.debug("$ack")
                        logger.warn("Order '${ack.orderId}' was refused at ${ack.timestamp.fmtDateTime()}")
                    }
                    else -> {
                        // TODO update pending transaction status
                        logger.debug("$ack")
                    }
                }
            }
        }
        SupportedDocument.PAIN_002 -> {
            val status = parseCustomerPaymentStatusReport(xml)
            if (status.paymentCode == ExternalPaymentGroupStatusCode.RJCT)
                logger.warn("Transaction '${status.id()}' was rejected")
            // TODO update pending transaction status
            logger.debug("$status")
        }
        SupportedDocument.CAMT_053, 
        SupportedDocument.CAMT_052 -> {
            // TODO parsing
            // TODO ingesting
        }
    }
}

private fun ingestDocuments(
    db: Database,
    currency: String,
    content: ByteArray,
    whichDocument: SupportedDocument
) {
    when (whichDocument) {
        SupportedDocument.CAMT_054,
        SupportedDocument.PAIN_002,
        SupportedDocument.CAMT_053, 
        SupportedDocument.CAMT_052 -> {
            try {
                content.unzipForEach { fileName, xmlContent ->
                    logger.trace("parse $fileName")
                    ingestDocument(db, currency, xmlContent, whichDocument)
                }
            } catch (e: IOException) {
                throw Exception("Could not open any ZIP archive", e)
            }
        }
        SupportedDocument.PAIN_002_LOGS -> ingestDocument(db, currency, content, whichDocument)
    }
}

/**
 * Fetches the banking records via EBICS notifications requests.
 *
 * It first checks the last execution_time (db column) among the
 * incoming transactions.  If that's not found, it asks the bank
 * about 'unseen notifications' (= does not specify any date range
 * in the request).  If that's found, it crafts a notification
 * request with such execution_time as the start date and now as
 * the end date.
 *
 * What this function does NOT do (now): linking documents between
 * different camt.05x formats and/or pain.002 acknowledgements.
 *
 * @param db database connection
 * @param ctx [FetchContext]
 * @param pinnedStart explicit start date for the downloaded documents.
 *        This parameter makes the last incoming transaction timestamp in
 *        the database IGNORED.  Only useful when running in --transient
 *        mode to download past documents / debug.
 */
private suspend fun fetchDocuments(
    db: Database,
    ctx: FetchContext,
    docs: List<Document>
): Boolean {
    val lastExecutionTime: Instant? = ctx.pinnedStart;
    return docs.all { doc ->
        try {
            if (lastExecutionTime != null) {
                logger.info("Fetching new '${doc.fullDescription()}'")
            } else {
                logger.info("Fetching '${doc.fullDescription()}' from timestamp: $lastExecutionTime")
            }
            val doc = doc.doc()
            // downloading the content
            val content = downloadHelper(ctx, lastExecutionTime, doc)
            if (!content.isEmpty()) {
                ctx.fileLogger.logFetch(
                    content,
                    doc == SupportedDocument.PAIN_002_LOGS
                )
                ingestDocuments(db, ctx.cfg.currency, content, doc)
            }
            true
        } catch (e: Exception) {
            e.fmtLog(logger)
            false
        }
    }
}

enum class Document {
    /// EBICS acknowledgement - CustomerAcknowledgement HAC pain.002
    acknowledgement,
    /// Payment status - CustomerPaymentStatusReport pain.002
    status,
    /// Account intraday reports - BankToCustomerAccountReport camt.052
    // report, TODO add support
    /// Debit & credit notifications - BankToCustomerDebitCreditNotification camt.054
    notification,
    /// Account statements - BankToCustomerStatement camt.053
    // statement, TODO add support
    ;

    fun shortDescription(): String = when (this) {
        Document.acknowledgement -> "EBICS acknowledgement"
        Document.status -> "Payment status"
        //Document.report -> "Account intraday reports"
        Document.notification -> "Debit & credit notifications"
        //Document.statement -> "Account statements"
    }

    fun fullDescription(): String = when (this) {
        Document.acknowledgement -> "EBICS acknowledgement - CustomerAcknowledgement HAC pain.002"
        Document.status -> "Payment status - CustomerPaymentStatusReport pain.002"
        //report -> "Account intraday reports - BankToCustomerAccountReport camt.052"
        Document.notification -> "Debit & credit notifications - BankToCustomerDebitCreditNotification camt.054"
        //statement -> "Account statements - BankToCustomerStatement camt.053"
    }

    fun doc(): SupportedDocument = when (this) {
        Document.acknowledgement -> SupportedDocument.PAIN_002_LOGS
        Document.status -> SupportedDocument.PAIN_002
        //Document.report -> SupportedDocument.CAMT_052
        Document.notification -> SupportedDocument.CAMT_054
        //Document.statement -> SupportedDocument.CAMT_053
    }
}

class EbicsFetch: CliktCommand("Fetches EBICS files") {
    private val common by CommonOption()
    private val transient by option(
        "--transient",
        help = "This flag fetches only once from the bank and returns, " +
                "ignoring the 'frequency' configuration value"
    ).flag(default = false)
    private val documents: Set<Document> by argument(
        help = "Which documents should be fetched? If none are specified, all supported documents will be fetched",
        helpTags = Document.entries.map { Pair(it.name, it.shortDescription()) }.toMap()
    ).enum<Document>().multiple().unique()
    private val pinnedStart by option(
        help = "Constant YYYY-MM-DD date for the earliest document" +
                " to download (only consumed in --transient mode).  The" +
                " latest document is always until the current time."
    )
    private val ebicsLog by option(
        "--debug-ebics",
        help = "Log EBICS content at SAVEDIR",
    )

    /**
     * This function collects the main steps of fetching banking records.
     * In this current version, it does not implement long polling, instead
     * it runs transient if FREQUENCY is zero.  Transient is also the default
     * mode when no flags are passed to the invocation.
     * FIXME: reduce code duplication with the submit subcommand.
     */
    override fun run() = cliCmd(logger, common.log) {
        val cfg: EbicsSetupConfig = extractEbicsConfig(common.config)
        val dbCfg = cfg.config.dbConfig()

        Database(dbCfg.dbConnStr).use { db ->
            val (clientKeys, bankKeys) = expectFullKeys(cfg)
            val ctx = FetchContext(
                cfg,
                HttpClient(),
                clientKeys,
                bankKeys,
                null,
                FileLogger(ebicsLog)
            )
            val docs = if (documents.isEmpty()) Document.entries else documents.toList()
            if (transient) {
                logger.info("Transient mode: fetching once and returning.")
                val pinnedStartVal = pinnedStart
                val pinnedStartArg = if (pinnedStartVal != null) {
                    logger.debug("Pinning start date to: $pinnedStartVal")
                    // Converting YYYY-MM-DD to Instant.
                    LocalDate.parse(pinnedStartVal).atStartOfDay(ZoneId.of("UTC")).toInstant()
                } else null
                ctx.pinnedStart = pinnedStartArg
                runBlocking {
                    if (!fetchDocuments(db, ctx, docs)) {
                        throw Exception("Failed to fetch documents")
                    }
                }
            } else {
                val configValue = cfg.config.requireString("nexus-fetch", "frequency")
                val frequencySeconds = checkFrequency(configValue)
                val cfgFrequency: NexusFrequency = NexusFrequency(frequencySeconds, configValue)
                logger.debug("Running with a frequency of ${cfgFrequency.fromConfig}")
                val frequency: NexusFrequency? = if (cfgFrequency.inSeconds == 0) {
                    logger.warn("Long-polling not implemented, running therefore in transient mode")
                    null
                } else {
                    cfgFrequency
                }
                runBlocking {
                    do {
                        fetchDocuments(db, ctx, docs)
                        delay(((frequency?.inSeconds ?: 0) * 1000).toLong())
                    } while (frequency != null)
                }
            }
        }
    }
}
