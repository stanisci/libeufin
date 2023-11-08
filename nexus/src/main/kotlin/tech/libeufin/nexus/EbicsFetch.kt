package tech.libeufin.nexus

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import tech.libeufin.util.ebics_h005.Ebics3Request
import tech.libeufin.util.getXmlDate
import java.time.Instant
import kotlin.concurrent.fixedRateTimer
import kotlin.system.exitProcess

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
 * Fetches the banking records via EBICS, calling the CAMT
 * parsing logic and finally updating the database accordingly.
 *
 * @param cfg config handle.
 * @param db database connection
 * @param httpClient HTTP client handle to reach the bank
 * @param clientKeys EBICS subscriber private keys.
 * @param bankKeys bank public keys.
 */
fun fetchHistory(
    cfg: EbicsSetupConfig,
    db: Database,
    httpClient: HttpClient,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile
) {
    throw NotImplementedError()
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
            fetchHistory(cfg, db, httpClient, clientKeys, bankKeys)
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
            fetchHistory(cfg, db, httpClient, clientKeys, bankKeys)
            return
        }
        fixedRateTimer(
            name = "ebics submit period",
            period = (frequency.inSeconds * 1000).toLong(),
            action = {
                fetchHistory(cfg, db, httpClient, clientKeys, bankKeys)
            }
        )
    }
}