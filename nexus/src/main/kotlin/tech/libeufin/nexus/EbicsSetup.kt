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
import tech.libeufin.ebics.ebics_h004.EbicsTypes
import kotlinx.serialization.encodeToString
import tech.libeufin.nexus.ebics.*
import tech.libeufin.common.*
import tech.libeufin.ebics.*
import tech.libeufin.ebics.ebics_h004.HTDResponseOrderData
import java.time.Instant
import kotlin.io.path.*
import java.nio.file.*

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
        parseEbicsHpbOrder(hpbBytes.inputStream())
    } catch (e: Exception) {
        throw Exception("HPB response content seems invalid", e)
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
    persistBankKeys(json, cfg.bankPublicKeysFilename)
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
    logger.info("Doing key request ${orderType.name}")
    val req = when(orderType) {
        KeysOrderType.INI -> generateIniMessage(cfg, privs)
        KeysOrderType.HIA -> generateHiaMessage(cfg, privs)
        KeysOrderType.HPB -> generateHpbMessage(cfg, privs)
    }
    val xml = try {
        client.postToBank(cfg.hostBaseUrl, req)
    } catch (e: Exception) {
        throw Exception("Could not POST the ${orderType.name} message to the bank at '${cfg.hostBaseUrl}'", e)
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
        persistClientKeys(privs, cfg.clientPrivateKeysFilename)
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
fun extractEbicsConfig(configFile: Path?): EbicsSetupConfig {
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
        val cfg = extractEbicsConfig(common.config)
        // Config is sane.  Go (maybe) making the private keys.
        val clientKeys = loadOrGenerateClientKeys(cfg.clientPrivateKeysFilename)
        val httpClient = HttpClient()
        // Privs exist.  Upload their pubs
        runBlocking {
            val keysNotSub = !clientKeys.submitted_ini
            if ((!clientKeys.submitted_ini) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.INI)
            // Eject PDF if the keys were submitted for the first time, or the user asked.
            if (keysNotSub || generateRegistrationPdf) makePdf(clientKeys, cfg)
            if ((!clientKeys.submitted_hia) || forceKeysResubmission)
                doKeysRequestAndUpdateState(cfg, clientKeys, httpClient, KeysOrderType.HIA)
        }
        // Checking if the bank keys exist on disk.
        var bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)
        if (bankKeys == null) {
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
            bankKeys = loadBankKeys(cfg.bankPublicKeysFilename)!!
        }

        if (!bankKeys.accepted) {
            // Finishing the setup by accepting the bank keys.
            if (autoAcceptKeys) bankKeys.accepted = true
            else bankKeys.accepted = askUserToAcceptKeys(bankKeys)

            if (!bankKeys.accepted) {
                throw Exception("Cannot successfully finish the setup without accepting the bank keys.")
            }
            try {
                persistBankKeys(bankKeys, cfg.bankPublicKeysFilename)
            } catch (e: Exception) {
                throw Exception("Could not set bank keys as accepted on disk.", e)
            }
        }
        
        println("setup ready")
    }
}