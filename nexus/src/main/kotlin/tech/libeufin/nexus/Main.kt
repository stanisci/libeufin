/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.
 * Copyright (C) 2024 Taler Systems S.A.

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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.io.path.*
import io.ktor.server.application.*
import org.slf4j.Logger
import org.slf4j.event.Level
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import tech.libeufin.common.api.*
import tech.libeufin.common.crypto.*
import tech.libeufin.common.db.DatabaseConfig
import tech.libeufin.nexus.api.*
import tech.libeufin.nexus.db.Database
import tech.libeufin.nexus.db.InitiatedPayment
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.EncryptedPrivateKeyInfo


internal val logger: Logger = LoggerFactory.getLogger("libeufin-nexus")

/**
 * Triple identifying one IBAN bank account.
 */
data class IbanAccountMetadata(
    val iban: String,
    val bic: String?,
    val name: String
)

fun Instant.fmtDate(): String = 
    DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("UTC")).format(this)

fun Instant.fmtDateTime(): String =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC")).format(this)


fun Application.nexusApi(db: Database, cfg: NexusConfig) = talerApi(logger) {
    wireGatewayApi(db, cfg)
    revenueApi(db, cfg)
}

/**
 * Abstracts the config loading
 *
 * @param configFile potentially NULL configuration file location.
 * @return the configuration handle.
 */
fun loadConfig(configFile: Path?): TalerConfig = NEXUS_CONFIG_SOURCE.fromFile(configFile)

/**
 * Abstracts fetching the DB config values to set up Nexus.
 */
fun TalerConfig.dbConfig(): DatabaseConfig =
    DatabaseConfig(
        dbConnStr = lookupString("libeufin-nexusdb-postgres", "config") ?: requireString("nexus-postgres", "config"),
        sqlDir = requirePath("libeufin-nexusdb-postgres", "sql_dir")
    )

class InitiatePayment: CliktCommand("Initiate an outgoing payment") {
    private val common by CommonOption()
    private val amount by option(
        "--amount",
        help = "The amount to transfer, payto 'amount' parameter takes the precedence"
    ).convert { TalerAmount(it) }
    private val subject by option(
        "--subject",
        help = "The payment subject, payto 'message' parameter takes the precedence"
    )
    private val requestUid by option(
        "--request-uid",
        help = "The payment request UID"
    )
    private val payto by argument(
        help = "The credited account IBAN payto URI"
    ).convert { Payto.parse(it).expectIban() }

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadConfig(common.config)
        val dbCfg = cfg.dbConfig()
        val currency = cfg.requireString("nexus-ebics", "currency")

        val subject = payto.message ?: subject ?: throw Exception("Missing subject")
        val amount = payto.amount ?: amount ?: throw Exception("Missing amount")

        if (payto.receiverName == null)
            throw Exception("Missing receiver name in creditor payto")

        if (amount.currency != currency)
            throw Exception("Wrong currency: expected $currency got ${amount.currency}")

        val requestUid = requestUid ?: run {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            Base32Crockford.encode(bytes)
        }

        Database(dbCfg, currency).use { db ->
            db.initiated.create(
                InitiatedPayment(
                    id = -1,
                    amount = amount,
                    wireTransferSubject = subject,
                    creditPaytoUri = payto.toString(),
                    initiationTime = Instant.now(),
                    requestUid = requestUid
                )
            )
        }
    }
}

class ConvertBackup: CliktCommand("Convert an old backup to the new config format") {
    private val backupPath by argument(
        "backup",
        help = "Specifies the backup file"
    ).path()

    @Serializable
    data class EbicsKeysBackupJson(
        val userID: String,
        val partnerID: String,
        val hostID: String,
        val ebicsURL: String,
        val authBlob: String,
        val encBlob: String,
        val sigBlob: String
    )

    override fun run() = cliCmd(logger, Level.INFO) {
        val raw = backupPath.readText()
        val backup = Json.decodeFromString<EbicsKeysBackupJson>(raw)

        val (authBlob, encBlob, sigBlob) = Triple(
            EncryptedPrivateKeyInfo(backup.authBlob.decodeBase64()),
            EncryptedPrivateKeyInfo(backup.encBlob.decodeBase64()),
            EncryptedPrivateKeyInfo(backup.sigBlob.decodeBase64())
        )
        lateinit var keys: ClientPrivateKeysFile
        while (true) {
            val passphrase = prompt("Enter the backup password", hideInput = true)!!
            try {
                val (authKey, encKey, sigKey) = Triple(
                    CryptoUtil.decryptKey(authBlob, passphrase),
                    CryptoUtil.decryptKey(encBlob, passphrase),
                    CryptoUtil.decryptKey(sigBlob, passphrase)
                )
                keys = ClientPrivateKeysFile(
                    signature_private_key = sigKey,
                    encryption_private_key = encKey,
                    authentication_private_key = authKey,
                    submitted_ini = false,
                    submitted_hia = false
                )
                break
            } catch (e: Exception) {
                e.fmtLog(logger)
            }
        }
       

        println("# KEYS")
        println(JSON.encodeToString(kotlinx.serialization.serializer<ClientPrivateKeysFile>(), keys))

        println("# CONFIG")
        println("""
[nexus-ebics]
CURRENCY = CHF

HOST_BASE_URL = ${backup.ebicsURL}
BANK_DIALECT = postfinance


HOST_ID = ${backup.hostID}
USER_ID = ${backup.userID}
PARTNER_ID = ${backup.partnerID}
SYSTEM_ID = 

IBAN = 
BIC = 
NAME = 
""")

        /*val (authKey, encKey, sigKey) = try {
            Triple(
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.authBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.encBlob)),
                    passphrase
                ),
                CryptoUtil.decryptKey(
                    EncryptedPrivateKeyInfo(base64ToBytes(ebicsBackup.sigBlob)),
                    passphrase
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            logger.info("Restoring keys failed, probably due to wrong passphrase")
            throw NexusError(
                HttpStatusCode.BadRequest,
                "Bad backup given"
            )
        }*/
    }
}

class FakeIncoming: CliktCommand("Genere a fake incoming payment") {
    private val common by CommonOption()
    private val amount by option(
        "--amount",
        help = "The amount to transfer, payto 'amount' parameter takes the precedence"
    ).convert { TalerAmount(it) }
    private val subject by option(
        "--subject",
        help = "The payment subject, payto 'message' parameter takes the precedence"
    )
    private val payto by argument(
        help = "The debited account IBAN payto URI"
    ).convert { Payto.parse(it).expectIban() }

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadConfig(common.config)
        val dbCfg = cfg.dbConfig()
        val currency = cfg.requireString("nexus-ebics", "currency")

        val subject = payto.message ?: subject ?: throw Exception("Missing subject")
        val amount = payto.amount ?: amount ?: throw Exception("Missing amount")

        if (amount.currency != currency)
            throw Exception("Wrong currency: expected $currency got ${amount.currency}")

        val bankId = run {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            Base32Crockford.encode(bytes)
        }

        Database(dbCfg, currency).use { db ->
            ingestIncomingPayment(db, 
                IncomingPayment(
                    amount = amount,
                    debitPaytoUri = payto.toString(),
                    wireTransferSubject = subject,
                    executionTime = Instant.now(),
                    bankId = bankId
                )
            )
        }
    }
}

class TestingCmd : CliktCommand("Testing helper commands", name = "testing") {
    init {
        subcommands(FakeIncoming(), ConvertBackup())
    }

    override fun run() = Unit
}

/**
 * Main CLI class that collects all the subcommands.
 */
class LibeufinNexusCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(EbicsSetup(), DbInit(), EbicsSubmit(), EbicsFetch(), InitiatePayment(), CliConfigCmd(NEXUS_CONFIG_SOURCE), TestingCmd())
    }
    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinNexusCommand().main(args)
}