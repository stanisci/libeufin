package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.EbicsOrderParams
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.getXmlDate
import tech.libeufin.util.toDbMicros
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.fixedRateTimer
import kotlin.io.path.createDirectories
import kotlin.reflect.typeOf
import kotlin.system.exitProcess

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
     * EBICS version.
     */
    val ebicsVersion: EbicsVersion = EbicsVersion.three,
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
 */
fun maybeLogFile(cfg: EbicsSetupConfig, content: ByteArray) {
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
    // Write each ZIP entry in the combined dir.
    content.unzipForEach { fileName, xmlContent ->
        val f  = File(dirs.toString(), "${now.toDbMicros()}_$fileName")
        // Rare: cannot download the same file twice in the same microsecond.
        if (f.exists()) {
            logger.error("Log file exists already at: ${f.path}")
            exitProcess(1)
        }
        doOrFail { f.writeText(xmlContent) }
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
    // maybe get last execution_date.
    val lastExecutionTime: Instant? = ctx.pinnedStart ?: db.incomingPaymentLastExecTime()
    logger.debug("Fetching documents from timestamp: $lastExecutionTime")
    val maybeContent = downloadHelper(ctx, lastExecutionTime) ?: exitProcess(1) // client is wrong, failing.
    if (maybeContent.isEmpty()) return
    maybeLogFile(ctx.cfg, maybeContent)
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

    private val pinnedStart by option(
        help = "constant YYYY-MM-DD date for the earliest document to download " +
                "(only consumed in --transient mode).  The latest document is always" +
                " until the current time."
    )

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

        val ctx = FetchContext(
            cfg,
            HttpClient(),
            clientKeys,
            bankKeys,
            whichDoc
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
