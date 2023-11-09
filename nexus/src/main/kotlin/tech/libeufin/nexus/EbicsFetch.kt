package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import tech.libeufin.nexus.ebics.EbicsSideError
import tech.libeufin.nexus.ebics.EbicsSideException
import tech.libeufin.nexus.ebics.createEbics3DownloadInitialization
import tech.libeufin.nexus.ebics.doEbicsDownload
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.getXmlDate
import tech.libeufin.util.toDbMicros
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.fixedRateTimer
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

/**
 * Unzips the ByteArray and runs the lambda over each entry.
 *
 * @param lambda function that gets the (fileName, fileContent) pair
 *        for each entry in the ZIP archive as input.
 */
fun ByteArray.unzipForEach(lambda: (String, String) -> Unit) {
    if (this.isEmpty()) {
        logger.warn("Empty archive")
        return
    }
    val mem = SeekableInMemoryByteChannel(this)
    val zipFile = ZipFile(mem)
    zipFile.getEntriesInPhysicalOrder().iterator().forEach {
        lambda(
            it.name, zipFile.getInputStream(it).readAllBytes().toString(Charsets.UTF_8)
        )
    }
    zipFile.close()
}

/**
 * Crafts a date range object, when the caller needs a time range.
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.
 * @return [Ebics3Request.DateRange]
 */
fun getEbics3DateRange(
    startDate: Instant,
    endDate: Instant
): Ebics3Request.DateRange {
    return Ebics3Request.DateRange().apply {
        start = getXmlDate(startDate)
        end = getXmlDate(endDate)
    }
}

/**
 * Prepares the request for a camt.054 notification from the bank.
 * Notifications inform the subscriber that some new events occurred
 * on their account.  One main difference with reports/statements is
 * that notifications - according to the ISO20022 documentation - do
 * NOT contain any balance.
 *
 * @param startDate inclusive starting date for the returned notification(s).
 * @param endDate inclusive ending date for the returned notification(s).  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 * @param isAppendix if true, the responded camt.054 will be an appendix of
 *        another camt.053 document, not therefore strictly acting as a notification.
 *        For example, camt.053 may omit wire transfer subjects and its related
 *        camt.054 appendix would instead contain those.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepNotificationRequest(
    startDate: Instant? = null,
    endDate: Instant? = null,
    isAppendix: Boolean
): Ebics3Request.OrderDetails.BTOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "REP"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.054"
            version = "08"
        }
        if (!isAppendix)
            serviceOption = "XDCI"
    }
    return Ebics3Request.OrderDetails.BTOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for a pain.002 acknowledgement from the bank.
 *
 * @param startDate inclusive starting date for the returned acknowledgements.
 * @param endDate inclusive ending date for the returned acknowledgements.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepAckRequest(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "PSR"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "pain.002"
            version = "10"
        }
    }
    return Ebics3Request.OrderDetails.BTOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for (a) camt.053/statement(s).
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepStatementRequest(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "EOP"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.053"
            version = "08"
        }
    }
    return Ebics3Request.OrderDetails.BTOrderParams().apply {
        this.service = service
        this.dateRange = if (startDate != null)
            getEbics3DateRange(startDate, endDate ?: Instant.now())
        else null
    }
}

/**
 * Prepares the request for camt.052/intraday records.
 *
 * @param startDate inclusive starting date for the returned banking events.
 * @param endDate inclusive ending date for the returned banking events.  NOTE:
 *        if startDate is NOT null and endDate IS null, endDate gets defaulted
 *        to the current UTC time.
 *
 * @return [Ebics3Request.OrderDetails.BTOrderParams]
 */
fun prepReportRequest(
    startDate: Instant? = null,
    endDate: Instant? = null
): Ebics3Request.OrderDetails.BTOrderParams {
    val service = Ebics3Request.OrderDetails.Service().apply {
        serviceName = "STM"
        scope = "CH"
        container = Ebics3Request.OrderDetails.Service.Container().apply {
            containerType = "ZIP"
        }
        messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
            value = "camt.052"
            version = "08"
        }
    }
    return Ebics3Request.OrderDetails.BTOrderParams().apply {
            this.service = service
            this.dateRange = if (startDate != null)
                getEbics3DateRange(startDate, endDate ?: Instant.now())
            else null
    }
}

/**
 * Downloads content via EBICS, according to the order params passed
 * by the caller.
 *
 * @param cfg configuration handle.
 * @param bankKeys bank public keys.
 * @param clientKeys EBICS subscriber private keys.
 * @param httpClient handle to the HTTP layer.
 * @param req contains the instructions for the download, namely
 *            which document is going to be downloaded from the bank.
 * @return the [ByteArray] payload.  On an empty response, the array
 *         length is zero.  It returns null, if the bank assigned an
 *         error to the EBICS transaction.
 */
suspend fun downloadRecords(
    cfg: EbicsSetupConfig,
    bankKeys: BankPublicKeysFile,
    clientKeys: ClientPrivateKeysFile,
    httpClient: HttpClient,
    req: Ebics3Request.OrderDetails.BTOrderParams
): ByteArray? {
    val initXml = createEbics3DownloadInitialization(
        cfg,
        bankKeys,
        clientKeys,
        orderParams = req
    )
    try {
        return doEbicsDownload(
            httpClient,
            cfg,
            clientKeys,
            bankKeys,
            initXml,
            isEbics3 = true,
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
    val maybeLogDir = cfg.config.lookupString(
        "[neuxs-fetch]",
        "STATEMENT_LOG_DIRECTORY"
    ) ?: return
    try { Path.of(maybeLogDir).createDirectories() }
    catch (e: Exception) {
        logger.error("Could not create log directory of path: $maybeLogDir")
        exitProcess(1)
    }
    val now = Instant.now()
    val asUtcDate = LocalDate.ofInstant(now, ZoneId.of("UTC"))
    content.unzipForEach { fileName, xmlContent ->
        val f = Path.of(
            "${asUtcDate.year}-${asUtcDate.monthValue}-${asUtcDate.dayOfMonth}",
            "${now.toDbMicros()}_$fileName"
        ).toFile()
        val completePath = Path.of(maybeLogDir, f.path)
        // Rare: cannot download the same file twice in the same microsecond.
        if (f.exists()) {
            logger.error("Log file exists already at: $completePath")
            exitProcess(1)
        }
        completePath.toFile().writeText(xmlContent)
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
 * @param cfg config handle.
 * @param db database connection
 * @param httpClient HTTP client handle to reach the bank
 * @param clientKeys EBICS subscriber private keys.
 * @param bankKeys bank public keys.
 */
suspend fun fetchHistory(
    cfg: EbicsSetupConfig,
    db: Database,
    httpClient: HttpClient,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile
) {
    // maybe get last execution_date.
    val lastExecutionTime = db.incomingPaymentLastExecTime()
    // Asking unseen records.
    val req = if (lastExecutionTime == null) prepNotificationRequest(isAppendix = false)
    else prepNotificationRequest(lastExecutionTime, isAppendix = false)
    val maybeContent = downloadRecords(
        cfg,
        bankKeys,
        clientKeys,
        httpClient,
        req
    ) ?: exitProcess(1) // client is wrong, failing.

    if (maybeContent.isEmpty()) return
    maybeLogFile(cfg, maybeContent)
}

class EbicsFetch: CliktCommand("Fetches bank records") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val transient by option(
        "--transient",
        help = "This flag fetches only once from the bank and returns, " +
                "ignoring the 'frequency' configuration value"
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
        val httpClient = HttpClient()
        if (transient) {
            logger.info("Transient mode: fetching once and returning.")
            runBlocking { fetchHistory(cfg, db, httpClient, clientKeys, bankKeys) }
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
            runBlocking { fetchHistory(cfg, db, httpClient, clientKeys, bankKeys) }
            return
        }
        fixedRateTimer(
            name = "ebics submit period",
            period = (frequency.inSeconds * 1000).toLong(),
            action = {
                runBlocking { fetchHistory(cfg, db, httpClient, clientKeys, bankKeys) }
            }
        )
    }
}