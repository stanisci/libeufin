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
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.groups.*
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.client.*
import io.ktor.util.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import tech.libeufin.nexus.ebics.*
import java.nio.file.Path
import java.time.Instant

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

/**
 * Contains the frequency of submit or fetch iterations.
 */
data class NexusFrequency(
    /**
     * Value in seconds of the FREQUENCY configuration
     * value, found either under [nexus-fetch] or [nexus-submit]
     */
    val inSeconds: Int,
    /**
     * Copy of the value found in the configuration.  Used
     * for logging.
     */
    val fromConfig: String
)

/**
 * Converts human-readable duration in how many seconds.  Supports
 * the suffixes 's' (seconds), 'm' (minute), 'h' (hours).  A valid
 * duration is therefore, for example, Nm, where N is the number of
 * minutes.
 *
 * @param trimmed duration
 * @return how many seconds is the duration input, or null if the input
 *         is not valid.
 */
fun getFrequencyInSeconds(humanFormat: String): Int? {
    val trimmed = humanFormat.trim()
    if (trimmed.isEmpty()) {
        logger.error("Input was empty")
        return null
    }
    val howManySeconds: Int = when (val lastChar = trimmed.last()) {
        's' -> {1}
        'm' -> {60}
        'h' -> {60 * 60}
        else -> {
            logger.error("Duration symbol not one of s, m, h.  '$lastChar' was found instead")
            return null
        }
    }
    val maybeNumber = trimmed.dropLast(1)
    val howMany = try {
        maybeNumber.trimEnd().toInt()
    } catch (e: Exception) {
        logger.error("Prefix was not a valid input: '$maybeNumber'")
        return null
    }
    if (howMany == 0) return 0
    val ret = howMany * howManySeconds
    if (howMany != ret / howManySeconds) {
        logger.error("Result overflew")
        return null
    }
    return ret
}

/**
 * Sanity-checks the frequency found in the configuration and
 * either returns it or fails the process.  Note: the returned
 * value is also guaranteed to be non-negative.
 *
 * @param foundInConfig frequency value as found in the configuration.
 * @return the duration in seconds of the value found in the configuration.
 */
fun checkFrequency(foundInConfig: String): Int {
    val frequencySeconds = getFrequencyInSeconds(foundInConfig)
        ?: throw Exception("Invalid frequency value in config section nexus-submit: $foundInConfig")
    if (frequencySeconds < 0) {
        throw Exception("Configuration error: cannot operate with a negative submit frequency ($foundInConfig)")
    }
    return frequencySeconds
}


/**
 * Keeps all the options of the ebics-setup subcommand.  The
 * caller has to handle TalerConfigError if values are missing.
 * If even one of the fields could not be instantiated, then
 * throws TalerConfigError.
 */
class EbicsSetupConfig(val config: TalerConfig) {
    // abstracts the section name.
    private val ebicsSetupRequireString = { option: String ->
        config.requireString("nexus-ebics", option)
    }
    private val ebicsSetupRequirePath = { option: String ->
        config.requirePath("nexus-ebics", option)
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
     * Bank account metadata.
     */
    val myIbanAccount = IbanAccountMetadata(
        iban = ebicsSetupRequireString("iban"),
        bic = ebicsSetupRequireString("bic"),
        name = ebicsSetupRequireString("name")
    )
    /**
     * Filename where we store the bank public keys.
     */
    val bankPublicKeysFilename = ebicsSetupRequirePath("bank_public_keys_file")
    /**
     * Filename where we store our private keys.
     */
    val clientPrivateKeysFilename = ebicsSetupRequirePath("client_private_keys_file")
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

        Database(dbCfg.dbConnStr).use { db ->
            db.initiatedPaymentCreate(
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

        Database(dbCfg.dbConnStr).use { db ->
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