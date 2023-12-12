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
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.groups.*
import io.ktor.client.*
import kotlinx.coroutines.runBlocking
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.io.File
import kotlin.system.exitProcess
import TalerConfigError
import kotlinx.serialization.encodeToString
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.time.Instant
import kotlin.reflect.typeOf

/**
 * Checks the configuration to secure that the key exchange between
 * the bank and the subscriber took place.  Helps to fail before starting
 * to talk EBICS to the bank.
 *
 * @param cfg configuration handle.
 * @return true if the keying was made before, false otherwise.
 */
fun isKeyingComplete(cfg: EbicsSetupConfig): Boolean {
    val maybeClientKeys = loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
    if (maybeClientKeys == null ||
        (!maybeClientKeys.submitted_ini) ||
        (!maybeClientKeys.submitted_hia)) {
        logger.error("Cannot operate without or with unsubmitted subscriber keys." +
                "  Run 'libeufin-nexus ebics-setup' first.")
        return false
    }
    val maybeBankKeys = loadBankKeys(cfg.bankPublicKeysFilename)
    if (maybeBankKeys == null || (!maybeBankKeys.accepted)) {
        logger.error("Cannot operate without or with unaccepted bank keys." +
                "  Run 'libeufin-nexus ebics-setup' until accepting the bank keys.")
        return false
    }
    return true
}

/**
 * Writes the JSON content to disk.  Used when we create or update
 * keys and other metadata JSON content to disk.  WARNING: this overrides
 * silently what's found under the given location!
 *
 * @param obj the class representing the JSON content to store to disk.
 * @param location where to store `obj`
 * @return true in case of success, false otherwise.
 */
inline fun <reified T> syncJsonToDisk(obj: T, location: String): Boolean {
    val fileContent = try {
        myJson.encodeToString(obj)
    } catch (e: Exception) {
        logger.error("Could not encode the input '${typeOf<T>()}' to JSON, detail: ${e.message}")
        return false
    }
    try {
        File(location).writeText(fileContent)
    } catch (e: Exception) {
        logger.error("Could not write JSON content at $location, detail: ${e.message}")
        return false
    }
    return true
}

/**
 * Generates new client private keys.
 *
 * @return [ClientPrivateKeysFile]
 */
fun generateNewKeys(): ClientPrivateKeysFile =
    ClientPrivateKeysFile(
        authentication_private_key = CryptoUtil.generateRsaKeyPair(2048).private,
        encryption_private_key = CryptoUtil.generateRsaKeyPair(2048).private,
        signature_private_key = CryptoUtil.generateRsaKeyPair(2048).private,
        submitted_hia = false,
        submitted_ini = false
    )
/**
 * Conditionally generates the client private keys and stores them
 * to disk, if the file does not exist already.  Does nothing if the
 * file exists.
 *
 * @param filename keys file location
 * @return true if the keys file existed already or its creation
 *         went through, false for any error.
 */
fun maybeCreatePrivateKeysFile(filename: String): Boolean {
    val f = File(filename)
    // NOT overriding any file at the wanted location.
    if (f.exists()) {
        logger.debug("Private key file found at: $filename.")
        return true
    }
    val newKeys = generateNewKeys()
    if (!syncJsonToDisk(newKeys, filename))
        return false
    logger.info("New client keys created at: $filename")
    return true
}

/**
 * Obtains the client private keys, regardless of them being
 * created for the first time, or read from an existing file
 * on disk.
 *
 * @param location path to the file that contains the keys.
 * @return true if the operation succeeds, false otherwise.
 */
private fun preparePrivateKeys(location: String): ClientPrivateKeysFile? {
    if (!maybeCreatePrivateKeysFile(location)) {
        logger.error("Could not create client keys at $location")
        exitProcess(1)
    }
    return loadPrivateKeysFromDisk(location) // loads what found at location.
}

/**
 * Expresses the type of keying message that the user wants
 * to send to the bank.
 */
enum class KeysOrderType {
    INI,
    HIA,
    HPB
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
    val encHash = CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_encryption_public_key).toHexString()
    val authHash = CryptoUtil.getEbicsPublicKeyHash(bankKeys.bank_authentication_public_key).toHexString()
    println("The bank has the following keys, type 'yes, accept' to accept them..\n")
    println("Encryption key: ${encHash.spaceEachTwo()}")
    println("Authentication key: ${authHash.spaceEachTwo()}")
    val userResponse: String? = readlnOrNull()
    if (userResponse == "yes, accept")
        return true
    return false
}

/**
 * Parses the HPB response and stores the bank keys as "NOT accepted" to disk.
 *
 * @param cfg used to get the location of the bank keys file.
 * @param bankKeys bank response to the HPB message.
 * @return true if the keys were stored to disk (as "not accepted"),
 *         false if the storage failed or the content was invalid.
 */
private fun handleHpbResponse(
    cfg: EbicsSetupConfig,
    bankKeys: EbicsKeyManagementResponseContent
): Boolean {
    val hpbBytes = bankKeys.orderData // silences compiler.
    if (hpbBytes == null) {
        logger.error("HPB content not found in a EBICS response with successful return codes.")
        return false
    }
    val hpbObj = try {
        parseEbicsHpbOrder(hpbBytes)
    }
    catch (e: Exception) {
        logger.error("HPB response content seems invalid.")
        return false
    }
    val encPub = try {
        CryptoUtil.loadRsaPublicKey(hpbObj.encryptionPubKey.encoded)
    } catch (e: Exception) {
        logger.error("Could not import bank encryption key from HPB response, detail: ${e.message}")
        return false
    }
    val authPub = try {
        CryptoUtil.loadRsaPublicKey(hpbObj.authenticationPubKey.encoded)
    } catch (e: Exception) {
        logger.error("Could not import bank authentication key from HPB response, detail: ${e.message}")
        return false
    }
    val json = BankPublicKeysFile(
        bank_authentication_public_key = authPub,
        bank_encryption_public_key = encPub,
        accepted = false
    )
    if (!syncJsonToDisk(json, cfg.bankPublicKeysFilename)) {
        logger.error("Failed to persist the bank keys to disk at: ${cfg.bankPublicKeysFilename}")
        return false
    }
    return true
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
 * @return true if the message fulfilled its purpose AND the state
 *         on disk was accordingly updated, or false otherwise.
 */
suspend fun doKeysRequestAndUpdateState(
    cfg: EbicsSetupConfig,
    privs: ClientPrivateKeysFile,
    client: HttpClient,
    orderType: KeysOrderType
): Boolean {
    logger.debug("Doing key request ${orderType.name}")
    val req = when(orderType) {
        KeysOrderType.INI -> generateIniMessage(cfg, privs)
        KeysOrderType.HIA -> generateHiaMessage(cfg, privs)
        KeysOrderType.HPB -> generateHpbMessage(cfg, privs)
    }
    val xml = client.postToBank(cfg.hostBaseUrl, req)
    if (xml == null) {
        logger.error("Could not POST the ${orderType.name} message to the bank")
        return false
    }
    val ebics = parseKeysMgmtResponse(privs.encryption_private_key, xml)
    if (ebics == null) {
        logger.error("Could not get any EBICS from the bank ${orderType.name} response ($xml).")
        return false
    }
    if (ebics.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        logger.error("EBICS ${orderType.name} failed with code: ${ebics.technicalReturnCode}")
        return false
    }
    if (ebics.bankReturnCode != EbicsReturnCode.EBICS_OK) {
        logger.error("EBICS ${orderType.name} reached the bank, but could not be fulfilled, error code: ${ebics.bankReturnCode}")
        return false
    }

    when(orderType) {
        KeysOrderType.INI -> privs.submitted_ini = true
        KeysOrderType.HIA -> privs.submitted_hia = true
        KeysOrderType.HPB -> return handleHpbResponse(cfg, ebics)
    }
    if (!syncJsonToDisk(privs, cfg.clientPrivateKeysFilename)) {
        logger.error("Could not update the ${orderType.name} state on disk")
        return false
    }
    return true
}

/**
 * Mere collector of the steps to load and parse the config.
 *
 * @param configFile location of the configuration entry point.
 * @return internal representation of the configuration.
 */
fun extractEbicsConfig(configFile: String?): EbicsSetupConfig {
    val config = loadConfigOrFail(configFile)
    // Checking the config.
    val cfg = try {
        EbicsSetupConfig(config)
    } catch (e: TalerConfigError) {
        logger.error(e.message)
        exitProcess(1)
    }
    return cfg
}

/**
 * Mere collector of the PDF generation steps.  Fails the
 * process if a problem occurs.
 *
 * @param privs client private keys.
 * @param cfg configuration handle.
 */
private fun makePdf(privs: ClientPrivateKeysFile, cfg: EbicsSetupConfig) {
    val pdf = generateKeysPdf(privs, cfg)
    val pdfFile = File("/tmp/libeufin-nexus-keys-${Instant.now().epochSecond}.pdf")
    if (pdfFile.exists()) {
        logger.error("PDF file exists already at: ${pdfFile.path}, not overriding it")
        exitProcess(1)
    }
    try {
        pdfFile.writeBytes(pdf)
    } catch (e: Exception) {
        logger.error("Could not write PDF to ${pdfFile}, detail: ${e.message}")
        exitProcess(1)
    }
    println("PDF file with keys hex encoding created at: $pdfFile")
}

/**
 * CLI class implementing the "ebics-setup" subcommand.
 */
class EbicsSetup: CliktCommand("Set up the EBICS subscriber") {
    private val common by CommonOption()
    private val checkFullConfig by option(
        help = "Checks config values of ALL the subcommands"
    ).flag(default = false)
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
    override fun run() {
        val cfg = doOrFail { extractEbicsConfig(common.config) }
        if (checkFullConfig) {
            doOrFail {
                cfg.config.requireString("nexus-submit", "frequency").apply {
                    if (getFrequencyInSeconds(this) == null)
                        throw Exception("frequency value of nexus-submit section is not valid: $this")
                }
                cfg.config.requireString("nexus-fetch", "frequency").apply {
                    if (getFrequencyInSeconds(this) == null)
                        throw Exception("frequency value of nexus-fetch section is not valid: $this")
                }
                cfg.config.requirePath("nexus-fetch", "statement_log_directory")
                cfg.config.requireNumber("nexus-httpd", "port")
                cfg.config.requirePath("nexus-httpd", "unixpath")
                cfg.config.requireString("nexus-httpd", "serve")
                cfg.config.requireString("nexus-httpd-wire-gateway-facade", "enabled")
                cfg.config.requireString("nexus-httpd-wire-gateway-facade", "auth_method")
                cfg.config.requireString("nexus-httpd-wire-gateway-facade", "auth_token")
                cfg.config.requireString("nexus-httpd-revenue-facade", "enabled")
                cfg.config.requireString("nexus-httpd-revenue-facade", "auth_method")
                cfg.config.requireString("nexus-httpd-revenue-facade", "auth_token")
            }
            return
        }
        // Config is sane.  Go (maybe) making the private keys.
        val privsMaybe = preparePrivateKeys(cfg.clientPrivateKeysFilename)
        if (privsMaybe == null) {
            logger.error("Private keys preparation failed.")
            exitProcess(1)
        }
        val httpClient = HttpClient()
        // Privs exist.  Upload their pubs
        val keysNotSub = !privsMaybe.submitted_ini || !privsMaybe.submitted_hia
        runBlocking {
            if ((!privsMaybe.submitted_ini) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, privsMaybe, httpClient, KeysOrderType.INI).apply { if (!this) exitProcess(1) }
            if ((!privsMaybe.submitted_hia) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, privsMaybe, httpClient, KeysOrderType.HIA).apply { if (!this) exitProcess(1) }
        }
        // Reloading new state from disk if any upload (and therefore a disk write) actually took place
        val haveSubmitted = forceKeysResubmission || keysNotSub
        val privs = if (haveSubmitted) {
            logger.info("Keys submitted to the bank, at ${cfg.hostBaseUrl}")
            loadPrivateKeysFromDisk(cfg.clientPrivateKeysFilename)
        } else privsMaybe
        if (privs == null) {
            logger.error("Could not reload private keys from disk after submission")
            exitProcess(1)
        }
        // Really both must be submitted here.
        if ((!privs.submitted_hia) || (!privs.submitted_ini)) {
            logger.error("Cannot continue with non-submitted client keys.")
            exitProcess(1)
        }
        // Eject PDF if the keys were submitted for the first time, or the user asked.
        if (keysNotSub || generateRegistrationPdf) makePdf(privs, cfg)
        // Checking if the bank keys exist on disk.
        val bankKeysFile = File(cfg.bankPublicKeysFilename)
        if (!bankKeysFile.exists()) {
            val areKeysOnDisk = runBlocking {
                doKeysRequestAndUpdateState(
                    cfg,
                    privs,
                    httpClient,
                    KeysOrderType.HPB
                )
            }
            if (!areKeysOnDisk) {
                logger.error("Could not download bank keys.  Send client keys (and/or related PDF document with --generate-registration-pdf) to the bank.")
                exitProcess(1)
            }
            logger.info("Bank keys stored at ${cfg.bankPublicKeysFilename}")
        }
        // bank keys made it to the disk, check if they're accepted.
        val bankKeysMaybe = loadBankKeys(cfg.bankPublicKeysFilename)
        if (bankKeysMaybe == null) {
            logger.error("Although previous checks, could not load the bank keys file from: ${cfg.bankPublicKeysFilename}")
            exitProcess(1)
        }
        val printOk = { println("setup ready") }

        if (bankKeysMaybe.accepted) {
            printOk()
            return
        }
        // Finishing the setup by accepting the bank keys.
        if (autoAcceptKeys) bankKeysMaybe.accepted = true
        else bankKeysMaybe.accepted = askUserToAcceptKeys(bankKeysMaybe)

        if (!bankKeysMaybe.accepted) {
            logger.error("Cannot successfully finish the setup without accepting the bank keys.")
            exitProcess(1)
        }
        if (!syncJsonToDisk(bankKeysMaybe, cfg.bankPublicKeysFilename)) {
            logger.error("Could not set bank keys as accepted on disk.")
            exitProcess(1)
        }
        printOk()
    }
}