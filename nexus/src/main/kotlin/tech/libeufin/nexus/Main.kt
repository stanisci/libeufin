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
import com.github.ajalt.clikt.parameters.types.*
import com.github.ajalt.clikt.core.ProgramResult
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.io.path.*
import kotlin.math.max
import io.ktor.server.application.*
import org.slf4j.Logger
import org.slf4j.event.Level
import org.slf4j.LoggerFactory
import tech.libeufin.common.*
import tech.libeufin.common.api.*
import tech.libeufin.common.crypto.*
import tech.libeufin.common.db.DatabaseConfig
import tech.libeufin.nexus.api.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.db.Database
import tech.libeufin.nexus.db.InitiatedPayment
import java.nio.file.Path
import java.time.*
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

class Serve : CliktCommand("Run libeufin-nexus HTTP server", name = "serve") {
    private val common by CommonOption()
    private val check by option().flag()

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadNexusConfig(common.config)
        
        if (check) {
            // Check if the server is to be started
            val apis = listOf(
                cfg.wireGatewayApiCfg to "Wire Gateway API",
                cfg.revenueApiCfg to "Revenue API"
            )
            var startServer = false
            for ((api, name) in apis) {
                if (api != null) {
                    startServer = true
                    logger.info("$name is enabled: starting the server")
                }
            }
            if (!startServer) {
                logger.info("All APIs are disabled: not starting the server")
                throw ProgramResult(1)
            } else {
                throw ProgramResult(0)
            }
        }

        val dbCfg = cfg.config.dbConfig()
        val serverCfg = cfg.config.loadServerConfig("nexus-httpd")
        Database(dbCfg, cfg.currency).use { db ->
            serve(serverCfg) {
                nexusApi(db, cfg)
            }
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
        val cfg = loadNexusConfig(common.config)
        val dbCfg = cfg.config.dbConfig()

        val subject = payto.message ?: subject ?: throw Exception("Missing subject")
        val amount = payto.amount ?: amount ?: throw Exception("Missing amount")

        if (amount.currency != cfg.currency)
            throw Exception("Wrong currency: expected ${cfg.currency} got ${amount.currency}")

        val bankId = run {
            val bytes = ByteArray(16)
            kotlin.random.Random.nextBytes(bytes)
            Base32Crockford.encode(bytes)
        }

        Database(dbCfg, amount.currency).use { db ->
            ingestIncomingPayment(db, 
                IncomingPayment(
                    amount = amount,
                    debitPaytoUri = payto.toString(),
                    wireTransferSubject = subject,
                    executionTime = Instant.now(),
                    bankId = bankId
                ),
                cfg.accountType
            )
        }
    }
}

enum class ListKind {
    incoming,
    outgoing,
    initiated;

    fun description(): String = when (this) {
        incoming -> "Incoming transactions"
        outgoing -> "Outgoing transactions"
        initiated -> "Initiated transactions"
    }
}

class EbicsDownload: CliktCommand("Perform EBICS requests", name = "ebics-btd") {
    private val common by CommonOption()
    private val type by option().default("BTD")
    private val name by option()
    private val scope by option()
    private val messageName by option()
    private val messageVersion by option()
    private val container by option()
    private val option by option()
    private val ebicsLog by option(
        "--debug-ebics",
        help = "Log EBICS content at SAVEDIR",
    )
    private val pinnedStart by option(
        help = "Constant YYYY-MM-DD date for the earliest document" +
                " to download (only consumed in --transient mode).  The" +
                " latest document is always until the current time."
    )
    private val dryRun by option().flag()

    class DryRun: Exception()

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadNexusConfig(common.config)
        val (clientKeys, bankKeys) = expectFullKeys(cfg)
        val pinnedStartVal = pinnedStart
        val pinnedStartArg = if (pinnedStartVal != null) {
            logger.debug("Pinning start date to: $pinnedStartVal")
            // Converting YYYY-MM-DD to Instant.
            LocalDate.parse(pinnedStartVal).atStartOfDay(ZoneId.of("UTC")).toInstant()
        } else null
        val client = HttpClient {
            install(HttpTimeout) {
                // It can take a lot of time for the bank to generate documents
                socketTimeoutMillis = 5 * 60 * 1000
            }
        }
        val fileLogger = FileLogger(ebicsLog)
        try {
            ebicsDownload(
                client,
                cfg,
                clientKeys,
                bankKeys,
                EbicsOrder.V3(type, name, scope, messageName, messageVersion, container, option),
                pinnedStartArg,
                null
            ) { stream ->
                if (container == "ZIP") {
                    val stream = fileLogger.logFetch(stream, false)
                    stream.unzipEach { fileName, xmlContent ->
                        println(fileName)
                        println(xmlContent.readBytes().toString(Charsets.UTF_8))
                    }
                } else {
                    val stream = fileLogger.logFetch(stream, true) // TODO better name
                    println(stream.readBytes().toString(Charsets.UTF_8))
                }
                if (dryRun) throw DryRun()
            }
        } catch (e: DryRun) {
            // We throw DryRun to not consume files while testing
        }        
    }
}

class ListCmd: CliktCommand("List nexus transactions", name = "list") {
    private val common by CommonOption()
    private val kind: ListKind by argument(
        help = "Which list to print",
        helpTags = ListKind.entries.map { Pair(it.name, it.description()) }.toMap()
    ).enum<ListKind>()

    override fun run() = cliCmd(logger, common.log) {
        val cfg = loadConfig(common.config)
        val dbCfg = cfg.dbConfig()
        val currency = cfg.requireString("nexus-ebics", "currency")

        Database(dbCfg, currency).use { db ->
            fun fmtPayto(payto: String?): String {
                if (payto == null) return ""
                try {
                    val parsed = Payto.parse(payto).expectIban()
                    return buildString {
                        append(parsed.iban.toString())
                        if (parsed.bic != null) append(" ${parsed.bic}")
                        if (parsed.receiverName != null) append(" ${parsed.receiverName}")
                    }
                } catch (e: Exception) {
                    return payto
                }
            }
            val (columnNames, rows) = when (kind) {
                ListKind.incoming -> {
                    val txs = db.payment.metadataIncoming()
                    Pair(
                        listOf(
                            "transaction", "id", "reserve_pub", "debtor", "subject"
                        ),
                        txs.map {
                            listOf(
                                "${it.date} ${it.amount}",
                                it.id,
                                it.reservePub?.toString() ?: "",
                                fmtPayto(it.debtor),
                                it.subject
                            )
                        }
                    )
                }
                ListKind.outgoing -> {
                    val txs = db.payment.metadataOutgoing()
                    Pair(
                        listOf(
                            "transaction", "id", "creditor", "subject"
                        ),
                        txs.map {
                            listOf(
                                "${it.date} ${it.amount}",
                                it.id,
                                fmtPayto(it.creditor),
                                it.subject ?: ""
                            )
                        }
                    )
                }
                ListKind.initiated -> {
                    val txs = db.payment.metadataInitiated()
                    Pair(
                        listOf(
                            "transaction", "id", "submission", "creditor", "status", "subject"
                        ),
                        txs.map {
                            listOf(
                                "${it.date} ${it.amount}",
                                it.id,
                                "${it.submissionTime} ${it.submissionCounter}",
                                fmtPayto(it.creditor),
                                "${it.status} ${it.msg ?: ""}".trim(),
                                it.subject
                            )
                        }
                    )
                }
            }
            val cols: List<Pair<String, Int>> = columnNames.mapIndexed { i, name -> 
                val maxRow: Int = rows.asSequence().map { it[i].length }.maxOrNull() ?: 0
                Pair(name, max(name.length, maxRow))
            }
            val table = buildString {
                fun padding(length: Int) {
                    repeat(length) { append (" ") }
                }
                var first = true
                for ((name, len) in cols) {
                    if (!first) {
                        append("|")
                    } else {
                        first = false
                    }
                    val pad = len - name.length
                    padding(pad / 2)
                    append(name)
                    padding(pad / 2 + if (pad % 2 == 0) { 0 } else { 1 })
                }
                append("\n")
                for (row in rows) {
                    var first = true
                    for ((met, str) in cols.zip(row)) {
                        if (!first) {
                            append("|")
                        } else {
                            first = false
                        }
                        val (name, len) = met
                        val pad = len - str.length
                        append(str)
                        padding(pad)
                    }
                    append("\n")
                }
            }
            print(table)
        }
    } 
}

class TestingCmd : CliktCommand("Testing helper commands", name = "testing") {
    init {
        subcommands(FakeIncoming(), ListCmd(), EbicsDownload())
    }

    override fun run() = Unit
}

/**
 * Main CLI class that collects all the subcommands.
 */
class LibeufinNexusCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(EbicsSetup(), DbInit(), Serve(), EbicsSubmit(), EbicsFetch(), InitiatePayment(), CliConfigCmd(NEXUS_CONFIG_SOURCE), TestingCmd())
    }
    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinNexusCommand().main(args)
}