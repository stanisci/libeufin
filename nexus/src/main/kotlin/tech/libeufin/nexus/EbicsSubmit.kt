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
import com.github.ajalt.clikt.parameters.groups.*
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.*
import kotlinx.coroutines.*
import tech.libeufin.common.*
import tech.libeufin.nexus.ebics.*
import java.time.*
import java.util.*

/**
 * Groups useful parameters to submit pain.001 via EBICS.
 */
data class SubmissionContext(
    /**
     * HTTP connection handle.
     */
    val httpClient: HttpClient,
    /**
     * Configuration handle.
     */
    val cfg: EbicsSetupConfig,
    /**
     * Subscriber EBICS private keys.
     */
    val clientPrivateKeysFile: ClientPrivateKeysFile,
    /**
     * Bank EBICS public keys.
     */
    val bankPublicKeysFile: BankPublicKeysFile,
    val fileLogger: FileLogger
)

/**
 * Takes the initiated payment data as it was returned from the
 * database, sanity-checks it, gets the pain.001 from the helper
 * function and finally submits it via EBICS to the bank.
 *
 * @param ctx [SubmissionContext]
 * @return true on success, false otherwise.
 */
private suspend fun submitInitiatedPayment(
    ctx: SubmissionContext,
    payment: InitiatedPayment
): String { 
    val creditAccount = try {
        val payto = Payto.parse(payment.creditPaytoUri).expectIban()
        IbanAccountMetadata(
            iban = payto.iban.value,
            bic = payto.bic,
            name = payto.receiverName!!
        )
    } catch (e: Exception) {
        throw e // TODO handle payto error
    }
    
    
    val xml = createPain001(
        requestUid = payment.requestUid,
        initiationTimestamp = payment.initiationTime,
        amount = payment.amount,
        creditAccount = creditAccount,
        debitAccount = ctx.cfg.myIbanAccount,
        wireTransferSubject = payment.wireTransferSubject
    )
    ctx.fileLogger.logSubmit(xml)
    return doEbicsUpload(
        ctx.httpClient,
        ctx.cfg,
        ctx.clientPrivateKeysFile,
        ctx.bankPublicKeysFile,
        uploadPaymentService(),
        xml
    )
}

/**
 * Searches the database for payments to submit and calls
 * the submitter helper.
 *
 * @param cfg configuration handle.
 * @param db database connection.
 * @param httpClient HTTP connection handle.
 * @param clientKeys subscriber private keys.
 * @param bankKeys bank public keys.
 */
private suspend fun submitBatch(
    ctx: SubmissionContext,
    db: Database,
) {
    logger.debug("Running submit at: ${Instant.now()}")
    db.initiatedPaymentsSubmittableGet(ctx.cfg.currency).forEach {
        logger.debug("Submitting payment initiation with row ID: ${it.id}")
        val submissionState = try {
            val orderId = submitInitiatedPayment(ctx, it)
            db.mem[orderId] = "Init"
            DatabaseSubmissionState.success
        } catch (e: Exception) {
            e.fmtLog(logger)
            DatabaseSubmissionState.transient_failure
            // TODO 
        }
        db.initiatedPaymentSetSubmittedState(it.id, submissionState)
    }
}

class EbicsSubmit : CliktCommand("Submits any initiated payment found in the database") {
    private val common by CommonOption()
    private val transient by option(
        "--transient",
        help = "This flag submits what is found in the database and returns, " +
                "ignoring the 'frequency' configuration value"
    ).flag(default = false)
    private val ebicsLog by option(
        "--debug-ebics",
        help = "Log EBICS content at SAVEDIR",
    )
    
    /**
     * Submits any initiated payment that was not submitted
     * so far and -- according to the configuration -- returns
     * or long-polls (currently not implemented) for new payments.
     * FIXME: reduce code duplication with the fetch subcommand.
     */
    override fun run() = cliCmd(logger, common.log) {
        val cfg: EbicsSetupConfig = extractEbicsConfig(common.config)
        val dbCfg = cfg.config.dbConfig()
        val (clientKeys, bankKeys) = expectFullKeys(cfg)
        val ctx = SubmissionContext(
            cfg = cfg,
            bankPublicKeysFile = bankKeys,
            clientPrivateKeysFile = clientKeys,
            httpClient = HttpClient(),
            fileLogger = FileLogger(ebicsLog)
        )
        Database(dbCfg.dbConnStr).use { db -> 
            val frequency = if (transient) {
                logger.info("Transient mode: submitting what found and returning.")
                null
            } else {
                val configValue = cfg.config.requireString("nexus-submit", "frequency")
                val frequencySeconds = checkFrequency(configValue)
                val frequency: NexusFrequency =  NexusFrequency(frequencySeconds, configValue)
                logger.debug("Running with a frequency of ${frequency.fromConfig}")
                if (frequency.inSeconds == 0) {
                    logger.warn("Long-polling not implemented, running therefore in transient mode")
                    null
                } else {
                    frequency
                }
            }
            do {
                // TODO error handling
                submitBatch(ctx, db)
                // TODO take submitBatch taken time in the delay
                delay(((frequency?.inSeconds ?: 0) * 1000).toLong())
            } while (frequency != null)
        }
    }
}