package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.groups.*
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import net.taler.wallet.crypto.Base32Crockford
import net.taler.wallet.crypto.EncodingException
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.concurrent.fixedRateTimer
import kotlin.io.path.*

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
     * Type of document to download.
     */
    val whichDocument: SupportedDocument,
    /**
     * EBICS version.  For the HAC message type, version gets switched to EBICS 2.
     */
    var ebicsVersion: EbicsVersion,
    /**
     * Logs to STDERR the init phase of an EBICS download request.
     */
    val ebicsExtraLog: Boolean,
    /**
     * Start date of the returned documents.  Only
     * used in --transient mode.
     */
    var pinnedStart: Instant? = null
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
    lastExecutionTime: Instant? = null
): ByteArray {
    val initXml = if (ctx.ebicsVersion == EbicsVersion.three) {
        createEbics3DownloadInitialization(
            ctx.cfg,
            ctx.bankKeys,
            ctx.clientKeys,
            prepEbics3Document(ctx.whichDocument, lastExecutionTime)
        )
    } else {
        val ebics2Req = prepEbics2Document(ctx.whichDocument, lastExecutionTime)
        createEbics25DownloadInit(
            ctx.cfg,
            ctx.clientKeys,
            ctx.bankKeys,
            ebics2Req.messageType,
            ebics2Req.orderParams
        )
    }
    if (ctx.ebicsExtraLog)
        logger.debug(initXml)
    try {
        return doEbicsDownload(
            ctx.httpClient,
            ctx.cfg,
            ctx.clientKeys,
            ctx.bankKeys,
            initXml,
            isEbics3 = ctx.ebicsVersion == EbicsVersion.three,
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
 * Extracts the archive entries and logs them to the location
 * optionally specified in the configuration.  It does nothing,
 * if the configuration lacks the log directory.
 *
 * @param cfg config handle.
 * @param content ZIP bytes from the server.
 * @param nonZip only true when downloading via HAC (EBICS 2)
 */
fun maybeLogFile(
    cfg: EbicsSetupConfig,
    content: ByteArray,
    nonZip: Boolean = false
) {
    // Main dir.
    val maybeLogDir = cfg.config.lookupString(
        "nexus-fetch",
        "STATEMENT_LOG_DIRECTORY"
    ) ?: return
    logger.debug("Logging to $maybeLogDir")
    // Subdir based on current day.
    val now = Instant.now()
    val asUtcDate = LocalDate.ofInstant(now, ZoneId.of("UTC"))
    val subDir = "${asUtcDate.year}-${asUtcDate.monthValue}-${asUtcDate.dayOfMonth}"
    // Creating the combined dir.
    val dirs = Path(maybeLogDir, subDir)
    dirs.createDirectories()
    if (nonZip) {
        val f = Path(dirs.toString(), "${now.toDbMicros()}_HAC_response.pain.002.xml")
        f.writeBytes(content)
    } else {
        // Write each ZIP entry in the combined dir.
        content.unzipForEach { fileName, xmlContent ->
            val f = Path(dirs.toString(), "${now.toDbMicros()}_$fileName")
            // Rare: cannot download the same file twice in the same microsecond.
            f.writeText(xmlContent)
        }
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
 * Gets Taler amount from a currency-agnostic value.
 *
 * @param noCurrencyAmount currency-agnostic value coming from the bank.
 * @param currency currency to set to the result.
 * @return [TalerAmount]
 */
fun getTalerAmount(
    noCurrencyAmount: String,
    currency: String,
    errorMessagePrefix: String = ""
): TalerAmount {
    if (currency.isEmpty()) throw Exception("Wrong helper invocation: currency is empty")
    val split = noCurrencyAmount.split(".")
    // only 1 (no fraction) or 2 (with fraction) sizes allowed.
    if (split.size != 1 && split.size != 2)
        throw Exception("${errorMessagePrefix}invalid amount: $noCurrencyAmount")
    val value = split[0].toLongOrNull()
        ?: throw Exception("${errorMessagePrefix}value part '${split[0]}' not a long")
    if (split.size == 1) return TalerAmount(
        value = value,
        fraction = 0,
        currency = currency
    )
    return TalerAmount(
        value = value,
        fraction = makeTalerFrac(split[1]),
        currency = currency
    )
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
 * Ingests any outgoing payment that was NOT ingested yet.  It
 * links it to the initiated outgoing transaction that originated
 * it.
 *
 * @param db database handle.
 * @param payment payment to (maybe) ingest.
 */
private suspend fun ingestOutgoingPayment(
    db: Database,
    payment: OutgoingPayment
) {
    val result = db.registerOutgoing(payment)
    if (result.new) {
        if (result.initiated)
            logger.debug("$payment")
        else 
            logger.debug("$payment recovered")
    } else {
        logger.debug("OUT '${payment.bankTransferId}' already seen")
    }
}

/**
 * Ingests any incoming payment that was NOT ingested yet.  Stores
 * the payment into valid talerable ones or bounces it, according
 * to the subject.
 *
 * @param db database handle.
 * @param currency fiat currency of the watched bank account.
 * @param payment payment to (maybe) ingest.
 */
private suspend fun ingestIncomingPayment(
    db: Database,
    payment: IncomingPayment
) {
    val reservePub = getTalerReservePub(payment)
    if (reservePub == null) {
        logger.debug("Incoming payment with UID '${payment.bankTransferId}'" +
                " has invalid subject: ${payment.wireTransferSubject}."
        )
        val result = db.registerMalformedIncoming(
            payment,
            payment.amount, 
            Instant.now()
        )
        if (result.new) {
            logger.debug("$payment bounced in '${result.bounceId}'")
        } else {
            logger.debug("IN '${payment.bankTransferId}' already seen and bounced in '${result.bounceId}'")
        }
    } else {
        val result = db.registerTalerableIncoming(payment, reservePub)
        if (result.new) {
            logger.debug("$payment")
        } else {
            logger.debug("IN '${payment.bankTransferId}' already seen")
        }
    }
}

/**
 * Compares amounts.
 *
 * @param a first argument
 * @param b second argument
 * @return true if the first argument
 *         is less than the second
 */
fun firstLessThanSecond(
    a: TalerAmount,
    b: TalerAmount
): Boolean {
    if (a.currency != b.currency)
        throw Exception("different currencies: ${a.currency} vs. ${b.currency}")
    if (a.value == b.value)
        return a.fraction < b.fraction
    return a.value < b.value
}

/**
 * Parses the response of an EBICS notification looking for
 * incoming payments.  As a result, it either creates a Taler
 * withdrawal or bounces the incoming payment.  In detail, this
 * function extracts the camt.054 from the ZIP archive, invokes
 * the lower-level camt.054 parser and updates the database.
 *
 * @param db database connection.
 * @param content the ZIP file that contains the EBICS
 *        notification as camt.054 records.
 */
private fun ingestNotification(
    db: Database,
    ctx: FetchContext,
    content: ByteArray
) {
    val incomingPayments = mutableListOf<IncomingPayment>()
    val outgoingPayments = mutableListOf<OutgoingPayment>()
    
    try {
        content.unzipForEach { fileName, xmlContent ->
            if (!fileName.contains("camt.054", ignoreCase = true))
                throw Exception("Asked for notification but did NOT get a camt.054")
            logger.debug("parse $fileName")
            parseTxNotif(xmlContent, ctx.cfg.currency, incomingPayments, outgoingPayments)
        }
    } catch (e: IOException) {
        throw Exception("Could not open any ZIP archive", e)
    }

    runBlocking {
        incomingPayments.forEach {
            ingestIncomingPayment(db, it)
        }
        outgoingPayments.forEach {
            ingestOutgoingPayment(db, it)
        }
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
    ctx: FetchContext
) {
    /**
     * Getting the least execution between the latest incoming
     * and outgoing payments.  This way, if ingesting outgoing
     * (incoming) payments crashed, we make sure we request from
     * the last successful outgoing (incoming) payment execution
     * time, to obtain again from the bank those payments that did
     * not make it to the database due to the crash.
     */
    val lastIncomingTime = db.incomingPaymentLastExecTime()
    val lastOutgoingTime = db.outgoingPaymentLastExecTime()
    val requestFrom: Instant? = minTimestamp(lastIncomingTime, lastOutgoingTime)

    val lastExecutionTime: Instant? = ctx.pinnedStart ?: requestFrom
    logger.debug("Fetching ${ctx.whichDocument} from timestamp: $lastExecutionTime")
    // downloading the content
    val maybeContent = downloadHelper(ctx, lastExecutionTime)
    if (maybeContent.isEmpty()) return
    // logging, if the configuration wants.
    maybeLogFile(
        ctx.cfg,
        maybeContent,
        nonZip = ctx.whichDocument == SupportedDocument.PAIN_002_LOGS
    )
    // Parsing the XML: only camt.054 (Detailavisierung) supported currently.
    if (ctx.whichDocument != SupportedDocument.CAMT_054) {
        logger.warn("Not ingesting ${ctx.whichDocument}.  Only camt.054 notifications supported.")
        return
    }
    try {
        ingestNotification(db, ctx, maybeContent)
    } catch (e: Exception) {
        throw Exception("Ingesting notifications failed", e)
    }
}

class EbicsFetch: CliktCommand("Fetches bank records.  Defaults to camt.054 notifications") {
    private val common by CommonOption()
    private val transient by option(
        "--transient",
        help = "This flag fetches only once from the bank and returns, " +
                "ignoring the 'frequency' configuration value"
    ).flag(default = false)

    private val onlyStatements by option(
        help = "Downloads only camt.053 statements"
    ).flag(default = false)

    private val onlyAck by option(
        help = "Downloads only pain.002 acknowledgements"
    ).flag(default = false)

    private val onlyReports by option(
        help = "Downloads only camt.052 intraday reports"
    ).flag(default = false)

    private val onlyLogs by option(
        help = "Downloads only EBICS activity logs via pain.002," +
                " only available to --transient mode.  Config needs" +
                " log directory"
    ).flag(default = false)

    private val pinnedStart by option(
        help = "Constant YYYY-MM-DD date for the earliest document" +
                " to download (only consumed in --transient mode).  The" +
                " latest document is always until the current time."
    )

    private val parse by option(
        help = "Reads one ISO20022 document from STDIN and prints " +
                "the parsing results.  It does not affect the database."
    ).flag(default = false)

    private val import by option(
        help = "Read one ISO20022 document from STDIN and imports its content into the database"
    ).flag(default = false)

    private val ebicsExtraLog by option(
        help = "Logs to STDERR the init phase of an EBICS download request"
    ).flag(default = false)

    /**
     * This function collects the main steps of fetching banking records.
     * In this current version, it does not implement long polling, instead
     * it runs transient if FREQUENCY is zero.  Transient is also the default
     * mode when no flags are passed to the invocation.
     * FIXME: reduce code duplication with the submit subcommand.
     */
    override fun run() = cliCmd(logger) {
        val cfg: EbicsSetupConfig = extractEbicsConfig(common.config)
        val dbCfg = cfg.config.dbConfig()

        // Deciding what to download.
        var whichDoc = SupportedDocument.CAMT_054
        if (onlyAck) whichDoc = SupportedDocument.PAIN_002
        if (onlyReports) whichDoc = SupportedDocument.CAMT_052
        if (onlyStatements) whichDoc = SupportedDocument.CAMT_053
        if (onlyLogs) whichDoc = SupportedDocument.PAIN_002_LOGS

        Database(dbCfg.dbConnStr).use { db ->
            if (parse || import) {
                logger.debug("Reading from STDIN, running in debug mode.  Not involving the database.")
                val maybeStdin = generateSequence(::readLine).joinToString("\n")
                when(whichDoc) {
                    SupportedDocument.CAMT_054 -> {
                        val incomingTxs = mutableListOf<IncomingPayment>()
                        val outgoingTxs = mutableListOf<OutgoingPayment>()
                        parseTxNotif(maybeStdin, cfg.currency, incomingTxs, outgoingTxs)
                        println(incomingTxs)
                        println(outgoingTxs)
                        if (import) {
                            runBlocking {
                                incomingTxs.forEach {
                                    ingestIncomingPayment(db, it)
                                }
                                outgoingTxs.forEach {
                                    ingestOutgoingPayment(db, it)
                                }
                            }
                        }
                    }
                    else -> throw Exception("Parsing $whichDoc not supported")
                }
                return@cliCmd
            }

            val (clientKeys, bankKeys) = expectFullKeys(cfg)
            val ctx = FetchContext(
                cfg,
                HttpClient(),
                clientKeys,
                bankKeys,
                whichDoc,
                EbicsVersion.three,
                ebicsExtraLog
            )
            if (transient) {
                logger.info("Transient mode: fetching once and returning.")
                val pinnedStartVal = pinnedStart
                val pinnedStartArg = if (pinnedStartVal != null) {
                    logger.debug("Pinning start date to: $pinnedStartVal")
                    // Converting YYYY-MM-DD to Instant.
                    LocalDate.parse(pinnedStartVal).atStartOfDay(ZoneId.of("UTC")).toInstant()
                } else null
                ctx.pinnedStart = pinnedStartArg
                if (whichDoc == SupportedDocument.PAIN_002_LOGS)
                    ctx.ebicsVersion = EbicsVersion.two
                runBlocking {
                    fetchDocuments(db, ctx)
                }
                return@cliCmd
            }
            val configValue = cfg.config.requireString("nexus-fetch", "frequency")
            val frequencySeconds = checkFrequency(configValue)
            val frequency: NexusFrequency = NexusFrequency(frequencySeconds, configValue)
            logger.debug("Running with a frequency of ${frequency.fromConfig}")
            if (frequency.inSeconds == 0) {
                logger.warn("Long-polling not implemented, running therefore in transient mode")
                runBlocking {
                    fetchDocuments(db, ctx)
                }
                return@cliCmd
            }
            fixedRateTimer(
                name = "ebics submit period",
                period = (frequency.inSeconds * 1000).toLong(),
                action = {
                    runBlocking {
                        fetchDocuments(db, ctx)
                    }
                }
            )
        }
    }
}
