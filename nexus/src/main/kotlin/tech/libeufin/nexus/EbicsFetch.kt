package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import net.taler.wallet.crypto.Base32Crockford
import net.taler.wallet.crypto.EncodingException
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.concurrent.fixedRateTimer
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess
import kotlin.text.StringBuilder

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
    var ebicsVersion: EbicsVersion = EbicsVersion.three,
    /**
     * Start date of the returned documents.  Only
     * used in --transient mode.
     */
    var pinnedStart: Instant? = null,
    /**
     * Logs to STDERR the init phase of an EBICS download request.
     */
    val ebicsExtraLog: Boolean = false
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
): ByteArray? {
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
        exitProcess(1)
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
    val dirs = Path.of(maybeLogDir, subDir)
    doOrFail { dirs.createDirectories() }
    fun maybeWrite(f: File, xml: String) {
        if (f.exists()) {
            logger.error("Log file exists already at: ${f.path}")
            exitProcess(1)
        }
        doOrFail { f.writeText(xml) }
    }
    if (nonZip) {
        val f  = File(dirs.toString(), "${now.toDbMicros()}_HAC_response.pain.002.xml")
        maybeWrite(f, content.toString(Charsets.UTF_8))
        return
    }
    // Write each ZIP entry in the combined dir.
    content.unzipForEach { fileName, xmlContent ->
        val f  = File(dirs.toString(), "${now.toDbMicros()}_$fileName")
        // Rare: cannot download the same file twice in the same microsecond.
        maybeWrite(f, xmlContent)
    }
}

/**
 * Converts the 2-digits fraction value as given by the bank
 * (postfinance dialect), to the Taler 8-digit value (db representation).
 *
 * @param bankFrac fractional value
 * @return the Taler fractional value with at most 8 digits.
 */
fun makeTalerFrac(bankFrac: String): Int {
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
    currency: String
): TalerAmount {
    if (currency.isEmpty()) throw Exception("Currency is empty")
    val split = noCurrencyAmount.split(".")
    // only 1 (no fraction) or 2 (with fraction) sizes allowed.
    if (split.size != 1 && split.size != 2) throw Exception("Invalid amount: ${noCurrencyAmount}")
    val value = split[0].toLongOrNull() ?: throw Exception("value part not a long")
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

class WrongPaymentDirection(val msg: String) : Exception(msg)

/**
 * Parses a camt.054 document looking for outgoing payments.
 *
 * @param notifXml input document.
 * @param acceptedCurrency currency accepted by Nexus
 * @return the list of outgoing payments.
 */
private fun parseOutgoingTxNotif(
    notifXml: String,
    acceptedCurrency: String,
): List<OutgoingPayment> {
    val ret = mutableListOf<OutgoingPayment>()
    notificationForEachTx(notifXml) { bookDate ->
        requireUniqueChildNamed("CdtDbtInd") {
            if (focusElement.textContent != "DBIT")
                throw WrongPaymentDirection("The payment is not outgoing, won't parse it")
        }
        // Obtaining the amount.
        val amount: TalerAmount = requireUniqueChildNamed("Amt") {
            val currency = focusElement.getAttribute("Ccy")
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            getTalerAmount(focusElement.textContent, currency)
        }

        /**
         * The MsgId extracted in the block below matches the one that
         * was specified as the MsgId element in the pain.001 that originated
         * this outgoing payment.  MsgId is considered unique because the
         * bank enforces its uniqueness.  Associating MsgId to this outgoing
         * payment is also convenient to match its initiated outgoing payment
         * in the database for reconciliation.
         */
        val uidFromBank = StringBuilder()
        requireUniqueChildNamed("Refs") {
            requireUniqueChildNamed("MsgId") {
                uidFromBank.append(focusElement.textContent)
            }
        }

        ret.add(OutgoingPayment(
            amount = amount,
            bankTransferId = uidFromBank.toString(),
            executionTime = bookDate
        ))
    }
    return ret
}

/**
 * Searches incoming payments in a camt.054 (Detailavisierung) document.
 *
 * @param notifXml camt.054 input document
 * @param acceptedCurrency currency accepted by Nexus.
 * @return the list of incoming payments to ingest in the database.
 */
private fun parseIncomingTxNotif(
    notifXml: String,
    acceptedCurrency: String
): List<IncomingPayment> {
    val ret = mutableListOf<IncomingPayment>()
    notificationForEachTx(notifXml) { bookDate ->
        // Check the direction first.
        requireUniqueChildNamed("CdtDbtInd") {
            if (focusElement.textContent != "CRDT")
                throw WrongPaymentDirection("The payment is not incoming, won't parse it")
        }
        val amount: TalerAmount = requireUniqueChildNamed("Amt") {
            val currency = focusElement.getAttribute("Ccy")
            if (currency != acceptedCurrency) throw Exception("Currency $currency not supported")
            getTalerAmount(focusElement.textContent, currency)
        }
        // Obtaining payment UID.
        val uidFromBank: String = requireUniqueChildNamed("Refs") {
            requireUniqueChildNamed("AcctSvcrRef") {
                focusElement.textContent
            }
        }
        // Obtaining payment subject.
        val subject = StringBuilder()
        requireUniqueChildNamed("RmtInf") {
            this.mapEachChildNamed("Ustrd") {
                val piece = this.focusElement.textContent
                subject.append(piece)
            }
        }

        // Obtaining the payer's details
        val debtorPayto = StringBuilder("payto://iban/")
        requireUniqueChildNamed("RltdPties") {
            requireUniqueChildNamed("DbtrAcct") {
                requireUniqueChildNamed("Id") {
                    requireUniqueChildNamed("IBAN") {
                        debtorPayto.append(focusElement.textContent)
                    }
                }
            }
            // warn: it might need the postal address too..
            requireUniqueChildNamed("Dbtr") {
                requireUniqueChildNamed("Nm") {
                    val urlEncName = URLEncoder.encode(focusElement.textContent, "utf-8")
                    debtorPayto.append("?receiver-name=$urlEncName")
                }
            }
        }
        ret.add(
            IncomingPayment(
                amount = amount,
                bankTransferId = uidFromBank,
                debitPaytoUri = debtorPayto.toString(),
                executionTime = bookDate,
                wireTransferSubject = subject.toString()
            )
        )
    }
    return ret
}

/**
 * Navigates the camt.054 (Detailavisierung) until its leaves, where
 * then it invokes the related parser, according to the payment direction.
 *
 * @param notifXml the input document.
 * @return any incoming payment as a list of [IncomingPayment]
 */
fun notificationForEachTx(
    notifXml: String,
    directionLambda: XmlElementDestructor.(Instant) -> Unit
) {
    val notifDoc = XMLUtil.parseStringIntoDom(notifXml)
    destructXml(notifDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("BkToCstmrDbtCdtNtfctn") {
                mapEachChildNamed("Ntfctn") {
                    mapEachChildNamed("Ntry") {
                        val bookDate: Instant = requireUniqueChildNamed("BookgDt") {
                            requireUniqueChildNamed("Dt") {
                                parseBookDate(focusElement.textContent)
                            }
                        }
                        mapEachChildNamed("NtryDtls") {
                            mapEachChildNamed("TxDtls") {
                                 directionLambda(this, bookDate)
                            }
                        }
                    }
                }
            }
        }
    }
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
 * @param db database connection.
 * @param payment incoming payment whose subject is to be checked.
 * @return [ByteArray] as the reserve public key, or null if the
 *         payment cannot lead to a Taler withdrawal.
 */
suspend fun getTalerReservePub(
    db: Database,
    payment: IncomingPayment
): ByteArray? {
    // Removing noise around the potential reserve public key.
    val maybeReservePub = removeSubjectNoise(payment.wireTransferSubject) ?: return null
    // Checking validity first.
    val dec = isReservePub(maybeReservePub) ?: return null
    // Now checking availability.
    val maybeUnavailable = db.isReservePubFound(dec)
    if (maybeUnavailable) {
        logger.error("Incoming payment with subject '${payment.wireTransferSubject}' exists already")
        return null
    }
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
    logger.debug("Ingesting outgoing payment UID ${payment.bankTransferId}, subject ${payment.wireTransferSubject}")
    // Check if the payment was ingested already.
    if (db.isOutgoingPaymentSeen(payment.bankTransferId)) {
        logger.debug("Outgoing payment with UID '${payment.bankTransferId}' already seen.")
        return
    }
    /**
     * Getting the initiate payment to link to this.  A missing initiated
     * payment could mean that a third party is downloading the bank account
     * history (to conduct an audit, for example)
     */
    val initId: Long? = db.initiatedPaymentGetFromUid(payment.bankTransferId);
    if (initId == null)
        logger.info("Outgoing payment lacks initiated counterpart with UID ${payment.bankTransferId}")
    // store the payment and its (maybe null) linked init
    val insertionResult = db.outgoingPaymentCreate(payment, initId)
    if (insertionResult != OutgoingPaymentOutcome.SUCCESS) {
        throw Exception("Could not store outgoing payment with UID " +
                "'${payment.bankTransferId}' and update its related initiation." +
                "  DB result: $insertionResult"
        )
    }
}

/**
 * Ingests any incoming payment that was NOT ingested yet.  Stores
 * the payment into valid talerable ones or bounces it, according
 * to the subject.
 *
 * @param db database handle.
 * @param incomingPayment payment to (maybe) ingest.
 */
private suspend fun ingestIncomingPayment(
    db: Database,
    incomingPayment: IncomingPayment
) {
    logger.debug("Ingesting incoming payment UID: ${incomingPayment.bankTransferId}, subject: ${incomingPayment.wireTransferSubject}")
    if (db.isIncomingPaymentSeen(incomingPayment.bankTransferId)) {
        logger.debug("Incoming payment with UID '${incomingPayment.bankTransferId}' already seen.")
        return
    }
    val reservePub = getTalerReservePub(db, incomingPayment)
    if (reservePub == null) {
        db.incomingPaymentCreateBounced(
            incomingPayment, UUID.randomUUID().toString().take(35)
        )
        return
    }
    db.incomingTalerablePaymentCreate(incomingPayment, reservePub)
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
 * @return true if the ingestion succeeded, false otherwise.
 *         False should fail the process, since it means that
 *         the notification could not be parsed.
 */
fun ingestNotification(
    db: Database,
    ctx: FetchContext,
    content: ByteArray
): Boolean {
    val incomingPayments = mutableListOf<IncomingPayment>()
    val outgoingPayments = mutableListOf<OutgoingPayment>()
    val filenamePrefixForIncoming = "camt.054_P_${ctx.cfg.myIbanAccount.iban}"
    val filenamePrefixForOutgoing = "camt.054-Debit_P_${ctx.cfg.myIbanAccount.iban}"
    try {
        content.unzipForEach { fileName, xmlContent ->
            if (!fileName.contains("camt.054", ignoreCase = true))
                throw Exception("Asked for notification but did NOT get a camt.054")
            /**
             * We ignore any camt.054 that does not bring Taler-relevant information,
             * like camt.054-Credit, for example.
             */
            if (!fileName.startsWith(filenamePrefixForIncoming) &&
                    !fileName.startsWith(filenamePrefixForOutgoing)) {
                logger.debug("Ignoring camt.054: $fileName")
                return@unzipForEach
            }

            if (fileName.startsWith(filenamePrefixForIncoming))
                incomingPayments += parseIncomingTxNotif(xmlContent, ctx.cfg.currency)
            else outgoingPayments += parseOutgoingTxNotif(xmlContent, ctx.cfg.currency)
        }
    } catch (e: IOException) {
        logger.error("Could not open any ZIP archive")
        return false
    } catch (e: Exception) {
        logger.error(e.message)
        return false
    }

    try {
        runBlocking {
            incomingPayments.forEach {
                ingestIncomingPayment(db, it)
            }
            outgoingPayments.forEach {
                ingestOutgoingPayment(db, it)
            }
        }
    } catch (e: Exception) {
        logger.error(e.message)
        return false
    }
    return true
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
    // maybe get last execution_date.
    val lastExecutionTime: Instant? = ctx.pinnedStart ?: db.incomingPaymentLastExecTime()
    logger.debug("Fetching ${ctx.whichDocument} from timestamp: $lastExecutionTime")
    // downloading the content
    val maybeContent = downloadHelper(ctx, lastExecutionTime) ?: exitProcess(1) // client is wrong, failing.
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
    if (!ingestNotification(db, ctx, maybeContent)) {
        logger.error("Ingesting notifications failed")
        exitProcess(1)
    }
}

class EbicsFetch: CliktCommand("Fetches bank records.  Defaults to camt.054 notifications") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
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
        help = "constant YYYY-MM-DD date for the earliest document" +
                " to download (only consumed in --transient mode).  The" +
                " latest document is always until the current time."
    )

    private val debug by option(
        help = "Reads one ISO20022 document from STDIN and prints " +
                "the parsing results.  It does not affect the database."
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
    override fun run() {
        val cfg: EbicsSetupConfig = doOrFail {
            extractEbicsConfig(configFile)
        }
        // Fail now if keying is incomplete.
        if (!isKeyingComplete(cfg)) exitProcess(1)
        val dbCfg = cfg.config.extractDbConfigOrFail()
        val db = Database(dbCfg.dbConnStr)
        val bankKeys = loadBankKeys(cfg.bankPublicKeysFilename) ?: exitProcess(1)
        if (!bankKeys.accepted) {
            logger.error("Bank keys are not accepted, yet.  Won't fetch any records.")
            exitProcess(1)
        }
        val clientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        if (clientKeys == null) {
            logger.error("Client private keys not found at: ${cfg.clientPrivateKeysFilename}")
            exitProcess(1)
        }

        // Deciding what to download.
        var whichDoc = SupportedDocument.CAMT_054
        if (onlyAck) whichDoc = SupportedDocument.PAIN_002
        if (onlyReports) whichDoc = SupportedDocument.CAMT_052
        if (onlyStatements) whichDoc = SupportedDocument.CAMT_053
        if (onlyLogs) whichDoc = SupportedDocument.PAIN_002_LOGS

        if (debug) {
            logger.debug("Reading from STDIN, running in debug mode.  Not involving the database.")
            val maybeStdin = generateSequence(::readLine).joinToString("\n")
            when(whichDoc) {
                SupportedDocument.CAMT_054 -> {
                    try {
                        println(parseIncomingTxNotif(maybeStdin, cfg.currency))
                    } catch (e: WrongPaymentDirection) {
                        logger.info("Input doesn't contain incoming payments")
                    }
                    try {
                        println(parseOutgoingTxNotif(maybeStdin, cfg.currency))
                    } catch (e: WrongPaymentDirection) {
                        logger.debug("Input doesn't contain outgoing payments")
                    }
                }
                else -> {
                    logger.error("Parsing $whichDoc not supported")
                    exitProcess(1)
                }
            }
            return
        }
        val ctx = FetchContext(
            cfg,
            HttpClient(),
            clientKeys,
            bankKeys,
            whichDoc,
            ebicsExtraLog = ebicsExtraLog
        )
        if (transient) {
            logger.info("Transient mode: fetching once and returning.")
            val pinnedStartVal = pinnedStart
            val pinnedStartArg = if (pinnedStartVal != null) {
                logger.debug("Pinning start date to: $pinnedStartVal")
                doOrFail {
                    // Converting YYYY-MM-DD to Instant.
                    LocalDate.parse(pinnedStartVal).atStartOfDay(ZoneId.of("UTC")).toInstant()
                }
            } else null
            ctx.pinnedStart = pinnedStartArg
            if (whichDoc == SupportedDocument.PAIN_002_LOGS)
                ctx.ebicsVersion = EbicsVersion.two
            runBlocking {
                fetchDocuments(db, ctx)
            }
            return
        }
        val frequency: NexusFrequency = doOrFail {
            val configValue = cfg.config.requireString("nexus-fetch", "frequency")
            val frequencySeconds = checkFrequency(configValue)
            return@doOrFail NexusFrequency(frequencySeconds, configValue)
        }
        logger.debug("Running with a frequency of ${frequency.fromConfig}")
        if (frequency.inSeconds == 0) {
            logger.warn("Long-polling not implemented, running therefore in transient mode")
            runBlocking {
                fetchDocuments(db, ctx)
            }
            return
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
