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
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.nexus.ebics.submitPayment
import tech.libeufin.util.parsePayto
import java.util.*
import kotlin.concurrent.fixedRateTimer
import kotlin.system.exitProcess

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
): Boolean {
    val creditor = parsePayto(initiatedPayment.creditPaytoUri)
    if (creditor?.receiverName == null) {
        logger.error("Won't create pain.001 without the receiver name")
        return false
    }
    if (initiatedPayment.wireTransferSubject == null) {
        logger.error("Won't create pain.001 without the wire transfer subject")
        return false
    }
    val xml = createPain001(
        requestUid = initiatedPayment.requestUid,
        initiationTimestamp = initiatedPayment.initiationTime,
        amount = initiatedPayment.amount,
        creditAccount = creditor,
        debitAccount = cfg.myIbanAccount,
        wireTransferSubject = initiatedPayment.wireTransferSubject
    )
    submitPayment(xml, cfg, clientPrivateKeysFile, bankPublicKeysFile, httpClient)
    return true
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
        maybeNumber.toInt()
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
    if (frequencySeconds == null) {
        throw Exception("Invalid frequency value in config section nexus-ebics-submit: $foundInConfig")
    }
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
    runBlocking {
        db.initiatedPaymentsUnsubmittedGet(cfg.currency).forEach {
            val submitted = submitInitiatedPayment(
                httpClient,
                cfg,
                clientKeys,
                bankKeys,
                it.value
            )
            /**
             * The following block tries to flag the initiated payment as submitted,
             * but it does NOT fail the process if the flagging fails.  This way, we
             * do NOT block other payments to be submitted.
             */
            if (submitted) {
                val flagged = db.initiatedPaymentSetSubmitted(it.key)
                if (!flagged) {
                    logger.warn("Initiated payment with row ID ${it.key} could not be flagged as submitted")
                }
            }
        }
    }
}

class EbicsSubmit : CliktCommand("Submits any initiated payment found in the database") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )
    /**
     * Submits any initiated payment that was not submitted
     * so far and -- according to the configuration -- returns
     * or long-polls (currently not implemented) for new payments.
     */
    override fun run() {
        val cfg: EbicsSetupConfig = doOrFail { extractEbicsConfig(configFile) }
        val frequency: Int = doOrFail {
            val configValue = cfg.config.requireString("nexus-ebics-submit", "frequency")
            return@doOrFail checkFrequency(configValue)
        }
        val dbCfg = cfg.config.extractDbConfigOrFail()
        val db = Database(dbCfg.dbConnStr)
        val httpClient = HttpClient()
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
        if (frequency == 0) {
            logger.warn("Long-polling not implemented, submitting what is found and exit")
            submitBatch(cfg, db, httpClient, clientKeys, bankKeys)
            return
        }
        fixedRateTimer(
            name = "ebics submit period",
            period = (frequency * 1000).toLong(),
            action = {
                submitBatch(cfg, db, httpClient, clientKeys, bankKeys)
            }
        )
    }
}