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
import tech.libeufin.nexus.ebics.submitPayment
import tech.libeufin.util.IbanPayto
import tech.libeufin.util.parsePayto
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
    initiatedPayment: InitiatedPayment,
    debtor: IbanPayto
): Boolean {
    val creditor = parsePayto(initiatedPayment.creditPaytoUri)
    if (creditor?.receiverName == null) {
        logger.error("Won't create pain.001 without the receiver name")
        return false
    }
    if (debtor.bic == null || debtor.receiverName == null) {
        logger.error("Won't create pain.001 without the debtor BIC and name")
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
        debitAccount = debtor,
        wireTransferSubject = initiatedPayment.wireTransferSubject
    )
    submitPayment(xml, cfg, clientPrivateKeysFile, bankPublicKeysFile, httpClient)
    return true
}

class EbicsSubmit : CliktCommand("Submits any initiated payment found in the database") {
    private val configFile by option(
        "--config", "-c",
        help = "set the configuration file"
    )

    /**
     * Submits any initiated payment that was not submitted
     * so far and -- according to the configuration -- returns
     * or long-polls for new payments.
     */
    override fun run() {
        val cfg = loadConfigOrFail(configFile)
        val frequency: Int = doOrFail {
            cfg.requireNumber("nexus-ebics-submit", "frequency")
        }
        if (frequency < 0) {
            logger.error("Configuration error: cannot operate with a negative submit frequency ($frequency)")
            exitProcess(1)
        }
        if (frequency == 0) {
            logger.error("Long-polling not implemented, set frequency > 0")
            exitProcess(1)
        }
        val dbCfg = cfg.extractDbConfigOrFail()
        val db = Database(dbCfg.dbConnStr)
        throw NotImplementedError("to be done")
    }
}