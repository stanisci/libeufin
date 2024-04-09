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
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import tech.libeufin.common.*
import tech.libeufin.nexus.db.*
import tech.libeufin.nexus.db.PaymentDAO.*
import tech.libeufin.nexus.ebics.*
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.*
import kotlin.io.path.*
import kotlin.time.toKotlinDuration

/**
 * Necessary data to perform a download.
 */
data class FetchContext(
    /**
     * Config handle.
     */
    val cfg: NexusConfig,
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
    val result = db.payment.registerOutgoing(payment)
    if (result.new) {
        if (result.initiated)
            logger.info("$payment")
        else 
            logger.warn("$payment recovered")
    } else {
        logger.debug("$payment already seen")
    }
}

private val PATTERN = Regex("[a-z0-9A-Z]{52}")

/**
 * Ingests an incoming payment.  Stores the payment into valid talerable ones
 * or bounces it, according to the subject.
 *
 * @param db database handle.
 * @param payment payment to (maybe) ingest.
 */
suspend fun ingestIncomingPayment(
    db: Database,
    payment: IncomingPayment
) {
    runCatching { parseIncomingTxMetadata(payment.wireTransferSubject) }.fold(
        onSuccess = { reservePub -> 
            val res = db.payment.registerTalerableIncoming(payment, reservePub)
            when (res) {
                IncomingRegistrationResult.ReservePubReuse -> throw Error("TODO reserve pub reuse")
                is IncomingRegistrationResult.Success -> {
                    if (res.new) {
                        logger.info("$payment")
                    } else {
                        logger.debug("$payment already seen")
                    }
                }
            }
        },
        onFailure = { e ->
            val result = db.payment.registerMalformedIncoming(
                payment,
                payment.amount, 
                Instant.now()
            )
            if (result.new) {
                logger.info("$payment bounced in '${result.bounceId}': ${e.fmt()}")
            } else {
                logger.debug("$payment already seen and bounced in '${result.bounceId}': ${e.fmt()}")
            }
        }
    )
}

private suspend fun ingestDocument(
    db: Database,
    cfg: NexusConfig,
    xml: InputStream,
    whichDocument: SupportedDocument
) {
    when (whichDocument) {
        SupportedDocument.CAMT_054 -> {
            try {
                parseTxNotif(xml, cfg.currency).forEach {
                    if (cfg.fetch.ignoreBefore != null && it.executionTime < cfg.fetch.ignoreBefore) {
                        logger.debug("IGNORE $it")
                    } else {
                        when (it) {
                            is IncomingPayment -> ingestIncomingPayment(db, it)
                            is OutgoingPayment -> ingestOutgoingPayment(db, it)
                            is TxNotification.Reversal -> {
                                logger.error("BOUNCE '${it.msgId}': ${it.reason}")
                                db.initiated.reversal(it.msgId, "Payment bounced: ${it.reason}")
                            }
                        }
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
                    HacAction.ORDER_HAC_FINAL_POS -> {
                        logger.debug("$ack")
                        db.initiated.logSuccess(ack.orderId!!)?.let { requestUID ->
                            logger.info("Payment '$requestUID' accepted at ${ack.timestamp.fmtDateTime()}")
                        }
                    }
                    HacAction.ORDER_HAC_FINAL_NEG -> {
                        logger.debug("{}", ack)
                        db.initiated.logFailure(ack.orderId!!)?.let { (requestUID, msg) ->
                            logger.error("Payment '$requestUID' refused at ${ack.timestamp.fmtDateTime()}${if (msg != null) ": $msg" else ""}")
                        }
                    }
                    else -> {
                        logger.debug("{}", ack)
                        if (ack.orderId != null) {
                            db.initiated.logMessage(ack.orderId, ack.msg())
                        }
                    }
                }
            }
        }
        SupportedDocument.PAIN_002 -> {
            val status = parseCustomerPaymentStatusReport(xml)
            val msg = status.msg()
            logger.debug("{}", status)
            if (status.paymentCode == ExternalPaymentGroupStatusCode.RJCT) {
                db.initiated.bankFailure(status.msgId, msg)
                logger.error("Transaction '${status.msgId}' was rejected : $msg")
            } else {
                db.initiated.bankMessage(status.msgId, msg)
            }
        }
        SupportedDocument.CAMT_053, 
        SupportedDocument.CAMT_052 -> {
            // TODO parsing
            // TODO ingesting
        }
    }
}

private suspend fun ingestDocuments(
    db: Database,
    cfg: NexusConfig,
    content: InputStream,
    whichDocument: SupportedDocument
) {
    when (whichDocument) {
        SupportedDocument.CAMT_054,
        SupportedDocument.PAIN_002,
        SupportedDocument.CAMT_053, 
        SupportedDocument.CAMT_052 -> {
            try {
                content.unzipEach { fileName, xmlContent ->
                    logger.trace("parse $fileName")
                    ingestDocument(db, cfg, xmlContent, whichDocument)
                }
            } catch (e: IOException) {
                throw Exception("Could not open any ZIP archive", e)
            }
        }
        SupportedDocument.PAIN_002_LOGS -> ingestDocument(db, cfg, content, whichDocument)
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
    docs: List<EbicsDocument>
): Boolean {
    val lastExecutionTime: Instant? = ctx.pinnedStart
    return docs.all { doc ->
        try {
            if (lastExecutionTime == null) {
                logger.info("Fetching new '${doc.fullDescription()}'")
            } else {
                logger.info("Fetching '${doc.fullDescription()}' from timestamp: $lastExecutionTime")
            }
            // downloading the content
            val doc = doc.doc()
            val order = downloadDocService(doc, doc == SupportedDocument.PAIN_002_LOGS)
            ebicsDownload(
                ctx.httpClient,
                ctx.cfg,
                ctx.clientKeys,
                ctx.bankKeys,
                order,
                lastExecutionTime,
                null
            ) { stream ->
                val loggedStream = ctx.fileLogger.logFetch(
                    stream,
                    doc == SupportedDocument.PAIN_002_LOGS
                )
                ingestDocuments(db, ctx.cfg, loggedStream, doc)
            }
            true
        } catch (e: Exception) {
            e.fmtLog(logger)
            false
        }
    }
}

enum class EbicsDocument {
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
        acknowledgement -> "EBICS acknowledgement"
        status -> "Payment status"
        //Document.report -> "Account intraday reports"
        notification -> "Debit & credit notifications"
        //Document.statement -> "Account statements"
    }

    fun fullDescription(): String = when (this) {
        acknowledgement -> "EBICS acknowledgement - CustomerAcknowledgement HAC pain.002"
        status -> "Payment status - CustomerPaymentStatusReport pain.002"
        //report -> "Account intraday reports - BankToCustomerAccountReport camt.052"
        notification -> "Debit & credit notifications - BankToCustomerDebitCreditNotification camt.054"
        //statement -> "Account statements - BankToCustomerStatement camt.053"
    }

    fun doc(): SupportedDocument = when (this) {
        acknowledgement -> SupportedDocument.PAIN_002_LOGS
        status -> SupportedDocument.PAIN_002
        //Document.report -> SupportedDocument.CAMT_052
        notification -> SupportedDocument.CAMT_054
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
    private val documents: Set<EbicsDocument> by argument(
        help = "Which documents should be fetched? If none are specified, all supported documents will be fetched",
        helpTags = EbicsDocument.entries.map { Pair(it.name, it.shortDescription()) }.toMap()
    ).enum<EbicsDocument>().multiple().unique()
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
     */
    override fun run() = cliCmd(logger, common.log) {
        val cfg = extractEbicsConfig(common.config)
        val dbCfg = cfg.config.dbConfig()

        Database(dbCfg).use { db ->
            val (clientKeys, bankKeys) = expectFullKeys(cfg)
            val ctx = FetchContext(
                cfg,
                HttpClient {
                    install(HttpTimeout) {
                        // It can take a lot of time for the bank to generate documents
                        socketTimeoutMillis = 5 * 60 * 1000
                    }
                },
                clientKeys,
                bankKeys,
                null,
                FileLogger(ebicsLog)
            )
            val docs = if (documents.isEmpty()) EbicsDocument.entries else documents.toList()
            if (transient) {
                logger.info("Transient mode: fetching once and returning.")
                val pinnedStartVal = pinnedStart
                val pinnedStartArg = if (pinnedStartVal != null) {
                    logger.debug("Pinning start date to: $pinnedStartVal")
                    // Converting YYYY-MM-DD to Instant.
                    LocalDate.parse(pinnedStartVal).atStartOfDay(ZoneId.of("UTC")).toInstant()
                } else null
                ctx.pinnedStart = pinnedStartArg
                if (!fetchDocuments(db, ctx, docs)) {
                    throw Exception("Failed to fetch documents")
                }
            } else {
                val raw = cfg.config.requireString("nexus-fetch", "frequency")
                logger.debug("Running with a frequency of $raw")
                if (cfg.fetch.frequency == Duration.ZERO) {
                    logger.warn("Long-polling not implemented, running therefore in transient mode")
                }
                do {
                    fetchDocuments(db, ctx, docs)
                    delay(cfg.fetch.frequency.toKotlinDuration())
                } while (cfg.fetch.frequency != Duration.ZERO)
            }
        }
    }
}
