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
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import tech.libeufin.common.db.DatabaseConfig
import tech.libeufin.nexus.db.Database
import tech.libeufin.nexus.db.InitiatedPayment
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val NEXUS_CONFIG_SOURCE = ConfigSource("libeufin", "libeufin-nexus", "libeufin-nexus")
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

class NexusFetchConfig(config: TalerConfig) {
    val frequency = config.requireDuration("nexus-fetch", "frequency")
    val ignoreBefore = config.lookupDate("nexus-fetch", "ignore_transactions_before")
}

/** Configuration for libeufin-nexus */
class NexusConfig(val config: TalerConfig) {
    private fun requireString(option: String): String = config.requireString("nexus-ebics", option)
    private fun requirePath(option: String): Path = config.requirePath("nexus-ebics", option)

    /** The bank's currency */
    val currency = requireString("currency")
    /** The bank base URL */
    val hostBaseUrl = requireString("host_base_url")
    /** The bank EBICS host ID */
    val ebicsHostId = requireString("host_id")
    /** EBICS user ID */
    val ebicsUserId = requireString("user_id")
    /** EBICS partner ID */
    val ebicsPartnerId = requireString("partner_id")
    /** Bank account metadata */
    val account = IbanAccountMetadata(
        iban = requireString("iban"),
        bic = requireString("bic"),
        name = requireString("name")
    )
    /** Path where we store the bank public keys */
    val bankPublicKeysPath = requirePath("bank_public_keys_file")
    /** Path where we store our private keys */
    val clientPrivateKeysPath = requirePath("client_private_keys_file")
    /**
     * A name that identifies the EBICS and ISO20022 flavour
     * that Nexus should honor in the communication with the
     * bank.
     */
    val bankDialect: String = requireString("bank_dialect").run {
        if (this != "postfinance") throw Exception("Only 'postfinance' dialect is supported.")
        return@run this
    }

    val fetch = NexusFetchConfig(config)
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

        Database(dbCfg).use { db ->
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

        Database(dbCfg).use { db ->
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
        subcommands(FakeIncoming())
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