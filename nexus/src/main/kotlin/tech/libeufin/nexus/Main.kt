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

/**
 * This file collects all the CLI subcommands and runs
 * them.  The actual implementation of each subcommand is
 * kept in their respective files.
 */
package tech.libeufin.nexus
import ConfigSource
import TalerConfig
import TalerConfigError
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.ktor.client.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import net.taler.wallet.crypto.Base32Crockford
import org.slf4j.event.Level
import tech.libeufin.nexus.ebics.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import kotlin.reflect.typeOf

val NEXUS_CONFIG_SOURCE = ConfigSource("libeufin-nexus", "libeufin-nexus")
val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus.Main")
val myJson = Json {
    this.serializersModule = SerializersModule {
        contextual(RSAPrivateCrtKey::class) { RSAPrivateCrtKeySerializer }
        contextual(RSAPublicKey::class) { RSAPublicKeySerializer }
    }
}

/**
 * Keeps all the options of the ebics-setup subcommand.  The
 * caller has to handle TalerConfigError if values are missing.
 * If even one of the fields could not be instantiated, then
 * throws TalerConfigError.
 */
class EbicsSetupConfig(config: TalerConfig) {
    // abstracts the section name.
    private val ebicsSetupRequireString = { option: String ->
        config.requireString("nexus-ebics", option)
    }
    // debug utility to inspect what was loaded.
    fun _dump() {
        this.javaClass.declaredFields.forEach {
            println("cfg obj: ${it.name} -> ${it.get(this)}")
        }
    }
    /**
     * The bank's currency.
     */
    val currency = ebicsSetupRequireString("currency")
    /**
     * The bank base URL.
     */
    val hostBaseUrl = ebicsSetupRequireString("host_base_url")
    /**
     * The bank EBICS host ID.
     */
    val ebicsHostId = ebicsSetupRequireString("host_id")
    /**
     * EBICS user ID.
     */
    val ebicsUserId = ebicsSetupRequireString("user_id")
    /**
     * EBICS partner ID.
     */
    val ebicsPartnerId = ebicsSetupRequireString("partner_id")
    /**
     * EBICS system ID (is this optional?).
     */
    val ebicsSystemId = ebicsSetupRequireString("system_id")
    /**
     * Bank account name, as given by the bank.  It
     * can be an IBAN or even any alphanumeric value.
     */
    val accountNumber = ebicsSetupRequireString("account_number")
    /**
     * Filename where we store the bank public keys.
     */
    val bankPublicKeysFilename = ebicsSetupRequireString("bank_public_keys_file")
    /**
     * Filename where we store our private keys.
     */
    val clientPrivateKeysFilename = ebicsSetupRequireString("client_private_keys_file")
    /**
     * Filename where we store the bank account main information.
     */
    val bankAccountMetadataFilename = ebicsSetupRequireString("account_meta_data_file")
    /**
     * A name that identifies the EBICS and ISO20022 flavour
     * that Nexus should honor in the communication with the
     * bank.
     */
    val bankDialect: String = ebicsSetupRequireString("bank_dialect").run {
        if (this != "postfinance") throw Exception("Only 'postfinance' dialect is supported.")
        return@run this
    }
}

/**
 * Converts base 32 representation of RSA public keys and vice versa.
 */
object RSAPublicKeySerializer : KSerializer<RSAPublicKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RSAPublicKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: RSAPublicKey) {
        encoder.encodeString(Base32Crockford.encode(value.encoded))
    }

    // Caller must handle exceptions here.
    override fun deserialize(decoder: Decoder): RSAPublicKey {
        val fieldValue = decoder.decodeString()
        val bytes = Base32Crockford.decode(fieldValue)
        return CryptoUtil.loadRsaPublicKey(bytes)
    }
}

/**
 * Converts base 32 representation of RSA private keys and vice versa.
 */
object RSAPrivateCrtKeySerializer : KSerializer<RSAPrivateCrtKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RSAPrivateCrtKey", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: RSAPrivateCrtKey) {
        encoder.encodeString(Base32Crockford.encode(value.encoded))
    }

    // Caller must handle exceptions here.
    override fun deserialize(decoder: Decoder): RSAPrivateCrtKey {
        val fieldValue = decoder.decodeString()
        val bytes = Base32Crockford.decode(fieldValue)
        return CryptoUtil.loadRsaPrivateKey(bytes)
    }
}

/**
 * Structure of the file that holds the bank account
 * metadata.
 */
@Serializable
data class BankAccountMetadataFile(
    val account_holder_iban: String,
    val bank_code: String?,
    val account_holder_name: String
)

/**
 * Structure of the JSON file that contains the client
 * private keys on disk.
 */
@Serializable
data class ClientPrivateKeysFile(
    // FIXME: centralize the @Contextual use.
    @Contextual val signature_private_key: RSAPrivateCrtKey,
    @Contextual val encryption_private_key: RSAPrivateCrtKey,
    @Contextual val authentication_private_key: RSAPrivateCrtKey,
    var submitted_ini: Boolean,
    var submitted_hia: Boolean
)

/**
 * Structure of the JSON file that contains the bank
 * public keys on disk.
 */
@Serializable
data class BankPublicKeysFile(
    @Contextual val bank_encryption_public_key: RSAPublicKey,
    @Contextual val bank_authentication_public_key: RSAPublicKey,
    var accepted: Boolean
)

/**
 * Load the bank keys file from disk.
 *
 * @param location the keys file location.
 * @return the internal JSON representation of the keys file,
 *         or null on failures.
 */
fun loadBankKeys(location: String): BankPublicKeysFile? {
    val f = File(location)
    if (!f.exists()) {
        logger.error("Could not find the bank keys file at: $location")
        return null
    }
    val fileContent = try {
        f.readText() // read from disk.
    } catch (e: Exception) {
        logger.error("Could not read the bank keys file from disk, detail: ${e.message}")
        return null
    }
    return try {
        myJson.decodeFromString(fileContent) // Parse into JSON.
    } catch (e: Exception) {
        logger.error(e.message)
        @OptIn(InternalAPI::class) // enables message below.
        logger.error(e.rootCause?.message) // actual useful message mentioning failing fields
        return null
    }
}

/**
 * Load the client keys file from disk.
 *
 * @param location the keys file location.
 * @return the internal JSON representation of the keys file,
 *         or null on failures.
 */
fun loadPrivateKeysFromDisk(location: String): ClientPrivateKeysFile? {
    val f = File(location)
    if (!f.exists()) {
        logger.error("Could not find the private keys file at: $location")
        return null
    }
    val fileContent = try {
        f.readText() // read from disk.
    } catch (e: Exception) {
        logger.error("Could not read private keys from disk, detail: ${e.message}")
        return null
    }
    return try {
        myJson.decodeFromString(fileContent) // Parse into JSON.
    } catch (e: Exception) {
        logger.error(e.message)
        @OptIn(InternalAPI::class) // enables message below.
        logger.error(e.rootCause?.message) // actual useful message mentioning failing fields
        return null
    }
}

/**
 * Main CLI class that collects all the subcommands.
 */
class LibeufinNexusCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(EbicsSetup())
    }
    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinNexusCommand().main(args)
}