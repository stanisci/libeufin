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
import TalerConfigError
import kotlinx.serialization.encodeToString
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.HTDResponseOrderData
import java.time.Instant
import kotlin.reflect.typeOf
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

/**
 * Writes the JSON content to disk.  Used when we create or update
 * keys and other metadata JSON content to disk.  WARNING: this overrides
 * silently what's found under the given location!
 *
 * @param obj the class representing the JSON content to store to disk.
 * @param path where to store `obj`
 */
inline fun <reified T> syncJsonToDisk(obj: T, path: String) {
    val content = try {
        myJson.encodeToString(obj)
    } catch (e: Exception) {
        throw Exception("Could not encode the input '${typeOf<T>()}' to JSON", e)
    }
    try {
        // Write to temp file then rename to enable atomicity when possible
        val path = Path(path).absolute()
        val tmp =  Files.createTempFile(path.parent, "tmp_", "_${path.fileName}")
        tmp.writeText(content)
        tmp.moveTo(path, StandardCopyOption.REPLACE_EXISTING);
    } catch (e: Exception) {
        throw Exception("Could not write JSON content at $path", e)
    }
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
 * Obtains the client private keys, regardless of them being
 * created for the first time, or read from an existing file
 * on disk.
 *
 * @param path path to the file that contains the keys.
 * @return current or new client keys
 */
private fun preparePrivateKeys(path: String): ClientPrivateKeysFile {
    // If exists load from disk
    val current = loadPrivateKeysFromDisk(path)
    if (current != null) return current
    // Else create new keys
    try {
        val newKeys = generateNewKeys()
        syncJsonToDisk(newKeys, path)
        logger.info("New client keys created at: $path")
        return newKeys
    } catch (e: Exception) {
        throw Exception("Could not create client keys at $path", e)
    }
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
 */
private fun handleHpbResponse(
    cfg: EbicsSetupConfig,
    bankKeys: EbicsKeyManagementResponseContent
) {
    val hpbBytes = bankKeys.orderData // silences compiler.
    if (hpbBytes == null) {
        throw Exception("HPB content not found in a EBICS response with successful return codes.")
    }
    val hpbObj = try {
        parseEbicsHpbOrder(hpbBytes)
    } catch (e: Exception) {
        throw Exception("HPB response content seems invalid: e")
    }
    val encPub = try {
        CryptoUtil.loadRsaPublicKey(hpbObj.encryptionPubKey.encoded)
    } catch (e: Exception) {
        throw Exception("Could not import bank encryption key from HPB response", e)
    }
    val authPub = try {
        CryptoUtil.loadRsaPublicKey(hpbObj.authenticationPubKey.encoded)
    } catch (e: Exception) {
        throw Exception("Could not import bank authentication key from HPB response", e)
    }
    val json = BankPublicKeysFile(
        bank_authentication_public_key = authPub,
        bank_encryption_public_key = encPub,
        accepted = false
    )
    try {
        syncJsonToDisk(json, cfg.bankPublicKeysFilename)
    } catch (e: Exception) {
        throw Exception("Failed to persist the bank keys to disk", e)
    }
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
    cfg: EbicsSetupConfig,
    privs: ClientPrivateKeysFile,
    client: HttpClient,
    orderType: KeysOrderType
) {
    logger.debug("Doing key request ${orderType.name}")
    val req = when(orderType) {
        KeysOrderType.INI -> generateIniMessage(cfg, privs)
        KeysOrderType.HIA -> generateHiaMessage(cfg, privs)
        KeysOrderType.HPB -> generateHpbMessage(cfg, privs)
    }
    val xml = client.postToBank(cfg.hostBaseUrl, req)
    if (xml == null) {
        throw Exception("Could not POST the ${orderType.name} message to the bank")
    }
    val ebics = parseKeysMgmtResponse(privs.encryption_private_key, xml)
    if (ebics == null) {
        throw Exception("Could not get any EBICS from the bank ${orderType.name} response ($xml).")
    }
    if (ebics.technicalReturnCode != EbicsReturnCode.EBICS_OK) {
        throw Exception("EBICS ${orderType.name} failed with code: ${ebics.technicalReturnCode}")
    }
    if (ebics.bankReturnCode != EbicsReturnCode.EBICS_OK) {
        throw Exception("EBICS ${orderType.name} reached the bank, but could not be fulfilled, error code: ${ebics.bankReturnCode}")
    }

    when (orderType) {
        KeysOrderType.INI -> privs.submitted_ini = true
        KeysOrderType.HIA -> privs.submitted_hia = true
        KeysOrderType.HPB -> return handleHpbResponse(cfg, ebics)
    }
    try {
        syncJsonToDisk(privs, cfg.clientPrivateKeysFilename)
    } catch (e: Exception) {
        throw Exception("Could not update the ${orderType.name} state on disk", e)
    }
}

/**
 * Mere collector of the steps to load and parse the config.
 *
 * @param configFile location of the configuration entry point.
 * @return internal representation of the configuration.
 */
fun extractEbicsConfig(configFile: String?): EbicsSetupConfig {
    val config = loadConfig(configFile)
    return EbicsSetupConfig(config)
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
        throw Exception("PDF file exists already at: ${pdfFile.path}, not overriding it")
    }
    try {
        pdfFile.writeBytes(pdf)
    } catch (e: Exception) {
        throw Exception("Could not write PDF to ${pdfFile}, detail: ${e.message}")
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
    override fun run() = cliCmd(logger, common.log) {
        val cfg = extractEbicsConfig(common.config)
        if (checkFullConfig) {
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
            return@cliCmd
        }
        // Config is sane.  Go (maybe) making the private keys.
        val clientKeys = preparePrivateKeys(cfg.clientPrivateKeysFilename)
        val httpClient = HttpClient()
        // Privs exist.  Upload their pubs
        val keysNotSub = !clientKeys.submitted_ini || !clientKeys.submitted_hia
        runBlocking {
            if ((!clientKeys.submitted_ini) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.INI)
            if ((!clientKeys.submitted_hia) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.HIA)
        }
        // Eject PDF if the keys were submitted for the first time, or the user asked.
        if (keysNotSub || generateRegistrationPdf) makePdf(clientKeys, cfg)
        // Checking if the bank keys exist on disk.
        val bankKeysFile = File(cfg.bankPublicKeysFilename)
        if (!bankKeysFile.exists()) {
            runBlocking {
                try {
                    doKeysRequestAndUpdateState(
                        cfg,
                        clientKeys,
                        httpClient,
                        KeysOrderType.HPB
                    )
                } catch (e: Exception) {
                    throw Exception("Could not download bank keys. Send client keys (and/or related PDF document with --generate-registration-pdf) to the bank", e)
                }
            }
            logger.info("Bank keys stored at ${cfg.bankPublicKeysFilename}")
        }
        // bank keys made it to the disk, check if they're accepted.
        val bankKeysMaybe = loadBankKeys(cfg.bankPublicKeysFilename)
        if (bankKeysMaybe == null) {
            throw Exception("Although previous checks, could not load the bank keys file from: ${cfg.bankPublicKeysFilename}")
        }

        if (!bankKeysMaybe.accepted) {
            // Finishing the setup by accepting the bank keys.
            if (autoAcceptKeys) bankKeysMaybe.accepted = true
            else bankKeysMaybe.accepted = askUserToAcceptKeys(bankKeysMaybe)

            if (!bankKeysMaybe.accepted) {
                throw Exception("Cannot successfully finish the setup without accepting the bank keys.")
            }
            try {
                syncJsonToDisk(bankKeysMaybe, cfg.bankPublicKeysFilename)
            } catch (e: Exception) {
                throw Exception("Could not set bank keys as accepted on disk.", e)
            }
        }
        
        println("setup ready")
    }
}