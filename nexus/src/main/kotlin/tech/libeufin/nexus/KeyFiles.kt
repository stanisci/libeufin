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

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import tech.libeufin.common.Base32Crockford
import tech.libeufin.common.crypto.CryptoUtil
import java.nio.file.*
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import kotlin.io.path.*

val JSON = Json {
    this.serializersModule = SerializersModule {
        contextual(RSAPrivateCrtKey::class) { RSAPrivateCrtKeySerializer }
        contextual(RSAPublicKey::class) { RSAPublicKeySerializer }
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
        return CryptoUtil.loadRSAPublic(bytes)
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
        return CryptoUtil.loadRSAPrivate(bytes)
    }
}

/**
 * Structure of the JSON file that contains the client
 * private keys on disk.
 */
@Serializable
data class ClientPrivateKeysFile(
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
 * Generates new client private keys.
 *
 * @return [ClientPrivateKeysFile]
 */
fun generateNewKeys(): ClientPrivateKeysFile =
    ClientPrivateKeysFile(
        authentication_private_key = CryptoUtil.genRSAPrivate(2048),
        encryption_private_key = CryptoUtil.genRSAPrivate(2048),
        signature_private_key = CryptoUtil.genRSAPrivate(2048),
        submitted_hia = false,
        submitted_ini = false
    )

inline fun <reified T> persistJsonFile(obj: T, path: Path, name: String) {
    val content = try {
        JSON.encodeToString(obj)
    } catch (e: Exception) {
        throw Exception("Could not encode $name", e)
    }
    val parent = try {
        path.parent ?: path.absolute().parent
    } catch (e: Exception) {
        throw Exception("Could not write $name at '$path'", e)
    }
    try {
        // Write to temp file then rename to enable atomicity when possible
        val tmp = Files.createTempFile(parent, "tmp_", "_${path.fileName}")
        tmp.writeText(content)
        tmp.moveTo(path, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        when {
            !parent.isWritable() -> throw Exception("Could not write $name at '$path': permission denied on '$parent'")
            !path.isWritable() -> throw Exception("Could not write $name at '$path': permission denied")
            else -> throw Exception("Could not write $name at '$path'", e)
        }
    }
}

/**
 * Persist the bank keys file to disk
 *
 * @param location the keys file location
 */
fun persistBankKeys(keys: BankPublicKeysFile, location: Path) = persistJsonFile(keys, location, "bank public keys")

/**
 * Persist the client keys file to disk
 *
 * @param location the keys file location
 */
fun persistClientKeys(keys: ClientPrivateKeysFile, location: Path) = persistJsonFile(keys, location, "client private keys")


inline fun <reified T> loadJsonFile(path: Path, name: String): T? {
    val content = try {
        path.readText()
    } catch (e: Exception) {
        when (e) {
            is NoSuchFileException -> return null
            is AccessDeniedException -> throw Exception("Could not read $name at '$path': permission denied")
            else -> throw Exception("Could not read $name at '$path'", e)
        }
    }
    return try {
        JSON.decodeFromString(content)
    } catch (e: Exception) {
        throw Exception("Could not decode $name at '$path'", e)
    }
}

/**
 * Load the bank keys file from disk.
 *
 * @param location the keys file location.
 * @return the internal JSON representation of the keys file,
 *         or null if the file does not exist
 */
fun loadBankKeys(location: Path): BankPublicKeysFile? = loadJsonFile(location, "bank public keys")

/**
 * Load the client keys file from disk.
 *
 * @param location the keys file location.
 * @return the internal JSON representation of the keys file,
 *         or null if the file does not exist
 */
fun loadClientKeys(location: Path): ClientPrivateKeysFile? = loadJsonFile(location, "client private keys")

/**
 * Load client and bank keys from disk.
 * Checks that the keying process has been fully completed.
 * 
 * Helps to fail before starting to talk EBICS to the bank.
 *
 * @param cfg configuration handle.
 * @return both client and bank keys
 */
fun expectFullKeys(cfg: NexusConfig): Pair<ClientPrivateKeysFile, BankPublicKeysFile> {
    val clientKeys = loadClientKeys(cfg.clientPrivateKeysPath)
    if (clientKeys == null) {
        throw Exception("Missing client private keys file at '${cfg.clientPrivateKeysPath}', run 'libeufin-nexus ebics-setup' first")
    } else if (!clientKeys.submitted_ini || !clientKeys.submitted_hia) {
        throw Exception("Unsubmitted client private keys, run 'libeufin-nexus ebics-setup' first")
    }
    val bankKeys = loadBankKeys(cfg.bankPublicKeysPath)
    if (bankKeys == null) {
        throw Exception("Missing bank public keys at '${cfg.bankPublicKeysPath}', run 'libeufin-nexus ebics-setup' first")
    } else if (!bankKeys.accepted) {
        throw Exception("Unaccepted bank public keys, run 'libeufin-nexus ebics-setup' until accepting the bank keys")
    }
    return Pair(clientKeys, bankKeys)
}