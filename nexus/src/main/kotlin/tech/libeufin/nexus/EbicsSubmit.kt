/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.nexus.ebics.EbicsSideError
import tech.libeufin.nexus.ebics.EbicsSideException
import tech.libeufin.nexus.ebics.EbicsUploadException
import tech.libeufin.nexus.ebics.submitPain001
import tech.libeufin.util.parsePayto
import tech.libeufin.util.toDbMicros
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

/**
 * Possible stages when an error may occur.  These stages
 * help to decide the retry policy.
 */
enum class NexusSubmissionStage {
    pain,
    ebics,
    /**
     * Includes both non-200 responses and network issues.
     * They are both considered transient (non-200 responses
     * can be fixed by changing and reloading the configuration).
     */
    reachability
}

/**
 * Expresses one error that occurred while submitting one pain.001
 * document via EBICS.
 */
class NexusSubmitException(
    msg: String? = null,
    cause: Throwable? = null,
    val stage: NexusSubmissionStage
) : Exception(msg, cause)

/**
 * Takes the initiated payment data, as it was returned from the
 * database, sanity-checks it, makes the pain.001 document and finally
 * submits it via EBICS to the bank.
 *
 * @param httpClient HTTP client to connect to the bank.
 * @param cfg configuration handle.  Contains the bank URL and EBICS IDs.
 * @param clientPrivateKeysFile client's private EBICS keys.
 * @param bankPublicKeysFile bank's public EBICS keys.
 * @param initiatedPayment payment initiation from the database.
 * @param debtor bank account information of the debited party.
 *               This values needs the BIC and the name.
 * @return true on success, false otherwise.
 */
private suspend fun submitInitiatedPayment(
    httpClient: HttpClient,
    cfg: EbicsSetupConfig,
    clientPrivateKeysFile: ClientPrivateKeysFile,
    bankPublicKeysFile: BankPublicKeysFile,
    initiatedPayment: InitiatedPayment
) {
    val creditor = parsePayto(initiatedPayment.creditPaytoUri)
    if (creditor?.receiverName == null)
        throw NexusSubmitException(
            "Won't create pain.001 without the receiver name",
            stage = NexusSubmissionStage.pain
        )
    val xml = createPain001(
        requestUid = initiatedPayment.requestUid,
        initiationTimestamp = initiatedPayment.initiationTime,
        amount = initiatedPayment.amount,
        creditAccount = creditor,
        debitAccount = cfg.myIbanAccount,
        wireTransferSubject = initiatedPayment.wireTransferSubject
    )
    try {
        submitPain001(
            xml,
            cfg,
            clientPrivateKeysFile,
            bankPublicKeysFile,
            httpClient
        )
    } catch (early: EbicsSideException) {
        val errorStage = when (early.sideEc) {
            EbicsSideError.HTTP_POST_FAILED ->
                NexusSubmissionStage.reachability // transient error
            /**
             * Any other [EbicsSideError] should be treated as permanent,
             * as they involve invalid signatures or an unexpected response
             * format.  For this reason, they get the "ebics" stage assigned
             * below, that will cause the payment as permanently failed and
             * not to be retried.
             */
            else ->
                NexusSubmissionStage.ebics // permanent error
        }
        throw NexusSubmitException(
            stage = errorStage,
            cause = early
        )
    } catch (permanent: EbicsUploadException) {
        throw NexusSubmitException(
            stage = NexusSubmissionStage.ebics,
            cause = permanent
        )
    }
    // Submission succeeded, storing the pain.001 to file.
    val logDir: String? = cfg.config.lookupString(
        "neuxs-submit",
        "SUBMISSIONS_LOG_DIRECTORY"
    )
    if (logDir != null) {
        val now = Instant.now()
        val asUtcDate = LocalDate.ofInstant(now, ZoneId.of("UTC"))
        val subDir = "${asUtcDate.year}-${asUtcDate.monthValue}-${asUtcDate.dayOfMonth}"
        val dirs = Path.of(logDir, subDir)
        doOrFail { dirs.createDirectories() }
        val f = File(
            dirs.toString(),
            "${now.toDbMicros()}_requestUid_${initiatedPayment.requestUid}_pain.001.xml"
            )
        // Very rare: same pain.001 should not be submitted twice in the same microsecond.
        if (f.exists()) {
            logger.error("pain.001 log file exists already at: $f")
            exitProcess(1)
        }
        doOrFail { f.writeText(xml) }
    }
}

/**
 * Converts human-readable duration in how many seconds.  Supports
 * the suffixes 's' (seconds), 'm' (minute), 'h' (hours).  A valid
 * duration is therefore, for example, Nm, where N is the number of
 * minutes.
 *
 * @param trimmed duration
 * @return how many seconds is the duration input, or null if the input
 *         is not valid.
 */
fun getFrequencyInSeconds(humanFormat: String): Int? {
    val trimmed = humanFormat.trim()
    if (trimmed.isEmpty()) {
        logger.error("Input was empty")
        return null
    }
    val lastChar = trimmed.last()
    val howManySeconds: Int = when (lastChar) {
        's' -> {1}
        'm' -> {60}
        'h' -> {60 * 60}
        else -> {
            logger.error("Duration symbol not one of s, m, h.  '$lastChar' was found instead")
            return null
        }
    }
    val maybeNumber = trimmed.dropLast(1)
    val howMany = try {
        maybeNumber.trimEnd().toInt()
    } catch (e: Exception) {
        logger.error("Prefix was not a valid input: '$maybeNumber'")
        return null
    }
    if (howMany == 0) return 0
    val ret = howMany * howManySeconds
    if (howMany != ret / howManySeconds) {
        logger.error("Result overflew")
        return null
    }
    return ret
}

/**
 * Sanity-checks the frequency found in the configuration and
 * either returns it or fails the process.  Note: the returned
 * value is also guaranteed to be non-negative.
 *
 * @param foundInConfig frequency value as found in the configuration.
 * @return the duration in seconds of the value found in the configuration.
 */
fun checkFrequency(foundInConfig: String): Int {
    val frequencySeconds = getFrequencyInSeconds(foundInConfig)
        ?: throw Exception("Invalid frequency value in config section nexus-submit: $foundInConfig")
    if (frequencySeconds < 0) {
        throw Exception("Configuration error: cannot operate with a negative submit frequency ($foundInConfig)")
    }
    return frequencySeconds
}

private fun submitBatch(
    cfg: EbicsSetupConfig,
    db: Database,
    httpClient: HttpClient,
    clientKeys: ClientPrivateKeysFile,
    bankKeys: BankPublicKeysFile
) {
    logger.debug("Running submit at: ${Instant.now()}")
    runBlocking {
        db.initiatedPaymentsUnsubmittedGet(cfg.currency).forEach {
            logger.debug("Submitting payment initiation with row ID: ${it.key}")
            val submissionState = try {
                submitInitiatedPayment(
                    httpClient,
                    cfg,
                    clientKeys,
                    bankKeys,
                    initiatedPayment = it.value
                )
                DatabaseSubmissionState.success
            } catch (e: NexusSubmitException) {
                logger.error(e.message)
                when (e.stage) {
                    /**
                     * Permanent failure: the pain.001 was invalid.  For example a Payto
                     * URI was missing the receiver name, or the currency was wrong.  Must
                     * not be retried.
                     */
                    NexusSubmissionStage.pain -> DatabaseSubmissionState.permanent_failure
                    /**
                     * Transient failure: HTTP or network failed, either because one party
                     * was offline / unreachable, or because the bank URL is wrong.  In both
                     * cases, the initiated payment stored in the database may still be correct,
                     * therefore we set this error as transient, and it'll be retried.
                     */
                    NexusSubmissionStage.reachability -> DatabaseSubmissionState.transient_failure
                    /**
                     * As in the pain.001 case, there is a fundamental problem in the document
                     * being submitted, so it should not be retried.
                     */
                    NexusSubmissionStage.ebics -> DatabaseSubmissionState.permanent_failure
                }
            }
            db.initiatedPaymentSetSubmittedState(it.key, submissionState)
        }
    }
}

class EbicsSubmit : CliktCommand("Submits any initiated payment found in the database") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    private val transient by option(
        "--transient",
        help = "This flag submits what is found in the database and returns, " +
                "ignoring the 'frequency' configuration value"
    ).flag(default = false)

    /**
     * Submits any initiated payment that was not submitted
     * so far and -- according to the configuration -- returns
     * or long-polls (currently not implemented) for new payments.
     * FIXME: reduce code duplication with the fetch subcommand.
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
            logger.error("Bank keys are not accepted, yet.  Won't submit any payment.")
            exitProcess(1)
        }
        val clientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        if (clientKeys == null) {
            logger.error("Client private keys not found at: ${cfg.clientPrivateKeysFilename}")
            exitProcess(1)
        }
        val httpClient = HttpClient()
        if (transient) {
            logger.info("Transient mode: submitting what found and returning.")
            submitBatch(cfg, db, httpClient, clientKeys, bankKeys)
            return
        }
        val frequency: NexusFrequency = doOrFail {
            val configValue = cfg.config.requireString("nexus-submit", "frequency")
            val frequencySeconds = checkFrequency(configValue)
            return@doOrFail NexusFrequency(frequencySeconds, configValue)
        }
        logger.debug("Running with a frequency of ${frequency.fromConfig}")
        if (frequency.inSeconds == 0) {
            logger.warn("Long-polling not implemented, running therefore in transient mode")
            submitBatch(cfg, db, httpClient, clientKeys, bankKeys)
            return
        }
        fixedRateTimer(
            name = "ebics submit period",
            period = (frequency.inSeconds * 1000).toLong(),
            action = {
                submitBatch(cfg, db, httpClient, clientKeys, bankKeys)
            }
        )
    }
}