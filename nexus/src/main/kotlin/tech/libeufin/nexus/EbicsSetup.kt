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
import io.ktor.client.plugins.*
import tech.libeufin.common.*
import tech.libeufin.common.crypto.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.ebics.EbicsKeyMng.Order.*
import java.nio.file.*
import java.time.Instant
import kotlin.io.path.*

/**
 * Obtains the client private keys, regardless of them being
 * created for the first time, or read from an existing file
 * on disk.
 *
 * @param path path to the file that contains the keys.
 * @return current or new client keys
 */
private fun loadOrGenerateClientKeys(path: Path): ClientPrivateKeysFile {
    // If exists load from disk
    val current = loadClientKeys(path)
    if (current != null) return current
    // Else create new keys
    val newKeys = generateNewKeys()
    persistClientKeys(newKeys, path)
    logger.info("New client private keys created at '$path'")
    return newKeys
}

/**
 * @return the "this" string with a space every two characters.
 */
fun String.spaceEachTwo() =
    buildString {
        this@spaceEachTwo.forEachIndexed { pos, c ->
            when {
                (pos == 0) -> this.append(c)
                (pos % 2 == 0) -> this.append(" $c")
                else -> this.append(c)
            }
        }
    }

/**
 * Asks the user to accept the bank public keys.
 *
 * @param bankKeys bank public keys, in format stored on disk.
 * @return true if the user accepted, false otherwise.
 */
private fun askUserToAcceptKeys(bankKeys: BankPublicKeysFile): Boolean {
    val encHash = CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_encryption_public_key).encodeUpHex()
    val authHash = CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_authentication_public_key).encodeUpHex()
    println("The bank has the following keys:")
    println("Encryption key: ${encHash.spaceEachTwo()}")
    println("Authentication key: ${authHash.spaceEachTwo()}")
    print("type 'yes, accept' to accept them: ")
    val userResponse: String? = readlnOrNull()
    return userResponse == "yes, accept"
}

/**
 * Collects all the steps from generating the message, to
 * sending it to the bank, and finally updating the state
 * on disk according to the response.
 *
 * @param cfg handle to the configuration.
 * @param privs bundle of all the private keys of the client.
 * @param client the http client that requests to the bank.
 * @param orderType INI or HIA.
 * @param autoAcceptBankKeys only given in case of HPB.  Expresses
 *        the --auto-accept-key CLI flag.
 */
suspend fun doKeysRequestAndUpdateState(
    cfg: NexusConfig,
    privs: ClientPrivateKeysFile,
    client: HttpClient,
    order: EbicsKeyMng.Order
) {
    logger.info("Doing key request $order")
    val req = EbicsKeyMng(cfg, privs, true).request(order)
    val xml = client.postToBank(cfg.hostBaseUrl, req, order.name)
    val resp = EbicsKeyMng.parseResponse(xml, privs.encryption_private_key)
    
    when (order) {
        INI, HIA -> {
            if (resp.technicalCode == EbicsReturnCode.EBICS_INVALID_USER_OR_USER_STATE) {
                throw Exception("$order status code ${resp.technicalCode}: either your IDs are incorrect, or you already have keys registered with this bank")
            }
        }
        HPB -> {
            if (resp.technicalCode == EbicsReturnCode.EBICS_AUTHENTICATION_FAILED) {
                throw Exception("$order status code ${resp.technicalCode}: could not download bank keys, send client keys (and/or related PDF document with --generate-registration-pdf) to the bank")
            }
        }
    }
    
    val orderData = resp.okOrFail(order.name)
    when (order) {
        INI -> privs.submitted_ini = true
        HIA -> privs.submitted_hia = true
        HPB -> {
            val orderData = requireNotNull(orderData) {
                "HPB: missing order data"
            }
            val (authPub, encPub) = EbicsKeyMng.parseHpbOrder(orderData)
            val bankKeys = BankPublicKeysFile(
                bank_authentication_public_key = authPub,
                bank_encryption_public_key = encPub,
                accepted = false
            )
            try {
                persistBankKeys(bankKeys, cfg.bankPublicKeysPath)
            } catch (e: Exception) {
                throw Exception("Could not update the $order state on disk", e)
            }
        }
    }
    if (order != HPB) {
        try {
            persistClientKeys(privs, cfg.clientPrivateKeysPath)
        } catch (e: Exception) {
            throw Exception("Could not update the $order state on disk", e)
        }
    }
    
}

/**
 * Mere collector of the steps to load and parse the config.
 *
 * @param configFile location of the configuration entry point.
 * @return internal representation of the configuration.
 */
fun loadNexusConfig(configFile: Path?): NexusConfig {
    val config = loadConfig(configFile)
    return NexusConfig(config)
}

/**
 * Mere collector of the PDF generation steps.  Fails the
 * process if a problem occurs.
 *
 * @param privs client private keys.
 * @param cfg configuration handle.
 */
private fun makePdf(privs: ClientPrivateKeysFile, cfg: NexusConfig) {
    val pdf = generateKeysPdf(privs, cfg)
    val path = Path("/tmp/libeufin-nexus-keys-${Instant.now().epochSecond}.pdf")
    try {
        path.writeBytes(pdf, StandardOpenOption.CREATE_NEW)
    } catch (e: Exception) {
        if (e is FileAlreadyExistsException) throw Exception("PDF file exists already at '$path', not overriding it")
        throw Exception("Could not write PDF to '$path'", e)
    }
    println("PDF file with keys created at '$path'")
}

/**
 * CLI class implementing the "ebics-setup" subcommand.
 */
class EbicsSetup: CliktCommand("Set up the EBICS subscriber") {
    private val common by CommonOption()
    private val forceKeysResubmission by option(
        help = "Resubmits all the keys to the bank"
    ).flag(default = false)
    private val autoAcceptKeys by option(
        help = "Accepts the bank keys without the user confirmation"
    ).flag(default = false)
    private val generateRegistrationPdf by option(
        help = "Generates the PDF with the client public keys to send to the bank"
    ).flag(default = false)
    /**
     * This function collects the main steps of setting up an EBICS access.
     */
    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadNexusConfig(common.config)
        val client = HttpClient {
            install(HttpTimeout) {
                // It can take a lot of time for the bank to generate documents
                socketTimeoutMillis = 5 * 60 * 1000
            }
        }

        val clientKeys = loadOrGenerateClientKeys(cfg.clientPrivateKeysPath)
        var bankKeys = loadBankKeys(cfg.bankPublicKeysPath)

        // Check EBICS 3 support
        val versions = HEV(client, cfg)
        logger.debug("HEV: $versions")
        if (!versions.contains(VersionNumber(3.0f, "H005")) && !versions.contains(VersionNumber(3.02f, "H005"))) {
            throw Exception("EBICS 3 is not supported by your bank")
        }

        // Privs exist.  Upload their pubs
        val keysNotSub = !clientKeys.submitted_ini
        if ((!clientKeys.submitted_ini) || forceKeysResubmission)
            doKeysRequestAndUpdateState(cfg, clientKeys, client, INI)
        // Eject PDF if the keys were submitted for the first time, or the user asked.
        if (keysNotSub || generateRegistrationPdf) makePdf(clientKeys, cfg)
        if ((!clientKeys.submitted_hia) || forceKeysResubmission)
            doKeysRequestAndUpdateState(cfg, clientKeys, client, HIA)
        
        // Checking if the bank keys exist on disk
        if (bankKeys == null) {
            doKeysRequestAndUpdateState(cfg, clientKeys, client, HPB)
            logger.info("Bank keys stored at ${cfg.bankPublicKeysPath}")
            bankKeys = loadBankKeys(cfg.bankPublicKeysPath)!!
        }

        if (!bankKeys.accepted) {
            // Finishing the setup by accepting the bank keys.
            if (autoAcceptKeys) bankKeys.accepted = true
            else bankKeys.accepted = askUserToAcceptKeys(bankKeys)

            if (!bankKeys.accepted) {
                throw Exception("Cannot successfully finish the setup without accepting the bank keys")
            }
            try {
                persistBankKeys(bankKeys, cfg.bankPublicKeysPath)
            } catch (e: Exception) {
                throw Exception("Could not set bank keys as accepted on disk", e)
            }
        }

        // Check account information
        logger.info("Doing administrative request HKD")
        try {
            ebicsDownload(client, cfg, clientKeys, bankKeys, EbicsOrder.V3("HKD"), null, null) { stream ->
                val hkd = EbicsAdministrative.parseHKD(stream)
                val account = hkd.account
                // TODO parse and check more information
                if (account.currency != null && account.currency != cfg.currency)
                    logger.error("Expected CURRENCY '${cfg.currency}' from config got '${account.currency}' from bank")
                if (account.iban != null && account.iban != cfg.account.iban)
                    logger.error("Expected IBAN '${cfg.account.iban}' from config got '${account.iban}' from bank")
                if (account.name != null && account.name != cfg.account.name)
                    logger.warn("Expected NAME '${cfg.account.name}' from config got '${account.name}' from bank")

                for (order in hkd.orders) {
                    logger.debug("${order.type}${order.params}: ${order.description}")
                }
            }
        } catch (e: Exception) {
            logger.warn("HKD failed: ${e.fmt()}")
        }
        
        println("setup ready")
    }
}