/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023-2024 Taler Systems S.A.

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

package tech.libeufin.bank

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.postgresql.util.PSQLState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.libeufin.bank.api.*
import tech.libeufin.bank.db.AccountDAO.*
import tech.libeufin.bank.db.Database
import tech.libeufin.common.*
import tech.libeufin.common.api.*
import tech.libeufin.common.db.dbInit
import tech.libeufin.common.db.pgDataSource
import java.net.InetAddress
import java.sql.SQLException
import java.time.Instant
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

private val logger: Logger = LoggerFactory.getLogger("libeufin-bank")



/**
 * Set up web server handlers for the Taler corebank API.
 */
fun Application.corebankWebApp(db: Database, ctx: BankConfig) = talerApi(logger) {
    coreBankApi(db, ctx)
    conversionApi(db, ctx)
    bankIntegrationApi(db, ctx)
    wireGatewayApi(db, ctx)
    revenueApi(db, ctx)
    ctx.spaPath?.let {
        get("/") {
            call.respondRedirect("/webui/")
        }
        staticFiles("/webui/", it.toFile())
    }
}

class BankDbInit : CliktCommand("Initialize the libeufin-bank database", name = "dbinit") {
    private val common by CommonOption()
    private val reset by option(
        "--reset", "-r",
        help = "Reset database (DANGEROUS: All existing data is lost)"
    ).flag()

    override fun run() = cliCmd(logger, common.log) {
        val config = talerConfig(common.config)
        val cfg = config.loadDbConfig()
        val ctx = config.loadBankConfig()
        pgDataSource(cfg.dbConnStr).dbInit(cfg, "libeufin-bank", reset)
        Database(cfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            // Create admin account if missing
            val res = createAdminAccount(db, ctx) // logs provided by the helper
            when (res) {
                AccountCreationResult.BonusBalanceInsufficient -> {}
                AccountCreationResult.LoginReuse -> {}
                AccountCreationResult.PayToReuse -> 
                    throw Exception("Failed to create admin's account")
                is AccountCreationResult.Success ->
                    logger.info("Admin's account created")
            }
        }
    }
}

class ServeBank : CliktCommand("Run libeufin-bank HTTP server", name = "serve") {
    private val common by CommonOption()

    override fun run() = cliCmd(logger, common.log) {
        val cfg = talerConfig(common.config)
        val ctx = cfg.loadBankConfig()
        val dbCfg = cfg.loadDbConfig()
        val serverCfg = cfg.loadServerConfig("libeufin-bank")
        Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            if (ctx.allowConversion) {
                logger.info("Ensure exchange account exists")
                val info = db.account.bankInfo("exchange", ctx.payto)
                if (info == null) {
                    throw Exception("Exchange account missing: an exchange account named 'exchange' is required for conversion to be enabled")
                } else if (!info.isTalerExchange) {
                    throw Exception("Account is not an exchange: an exchange account named 'exchange' is required for conversion to be enabled")
                }
                logger.info("Ensure conversion is enabled")
                val sqlProcedures = Path("${dbCfg.sqlDir}/libeufin-conversion-setup.sql")
                if (!sqlProcedures.exists()) {
                    throw Exception("Missing libeufin-conversion-setup.sql file")
                }
                db.conn { it.execSQLUpdate(sqlProcedures.readText()) }
            } else {
                logger.info("Ensure conversion is disabled")
                val sqlProcedures = Path("${dbCfg.sqlDir}/libeufin-conversion-drop.sql")
                if (!sqlProcedures.exists()) {
                    throw Exception("Missing libeufin-conversion-drop.sql file")
                }
                db.conn { it.execSQLUpdate(sqlProcedures.readText()) }
                // Remove conversion info from the database ?
            }
            serve(serverCfg) {
                corebankWebApp(db, ctx)
            }
        }
    }
}

class ChangePw : CliktCommand("Change account password", name = "passwd") {
    private val common by CommonOption()
    private val username by argument("username", help = "Account username")
    private val password by argument(
        "password", 
        help = "Account password used for authentication"
    )

    override fun run() = cliCmd(logger, common.log) {
        val cfg = talerConfig(common.config)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()
        Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            val res = db.account.reconfigPassword(username, password, null, true)
            when (res) {
                AccountPatchAuthResult.UnknownAccount ->
                    throw Exception("Password change for '$username' account failed: unknown account")
                AccountPatchAuthResult.OldPasswordMismatch,
                    AccountPatchAuthResult.TanRequired -> { /* Can never happen */ }
                AccountPatchAuthResult.Success ->
                    logger.info("Password change for '$username' account succeeded")
            }
        }
    }
}


class EditAccount : CliktCommand(
    "Edit an existing account",
    name = "edit-account"
) {
    private val common by CommonOption()
    private val username: String by argument(
        "username",
        help = "Account username"
    )
    private val name: String? by option(
        help = "Legal name of the account owner"
    )
    private val exchange: Boolean? by option(
        hidden = true
    ).boolean()
    private val is_public: Boolean? by option(
        "--public",
        help = "Make this account visible to anyone"
    ).boolean()
    private val email: String? by option(help = "E-Mail address used for TAN transmission")
    private val phone: String? by option(help = "Phone number used for TAN transmission")
    private val tan_channel: String? by option(help = "which channel TAN challenges should be sent to")
    private val cashout_payto_uri: IbanPayto? by option(help = "Payto URI of a fiant account who receive cashout amount").convert { Payto.parse(it).expectIban() }
    private val debit_threshold: TalerAmount? by option(help = "Max debit allowed for this account").convert { TalerAmount(it) }
    private val min_cashout: Option<TalerAmount>? by option(help = "Custom minimum cashout amount for this account").convert {
        if (it == "") {
            Option.None
        } else {
            Option.Some(TalerAmount(it))
        }
    }

    override fun run() = cliCmd(logger, common.log) {
        val cfg = talerConfig(common.config)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()
        Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            val req = AccountReconfiguration(
                name = name,
                is_taler_exchange = exchange,
                is_public = is_public,
                contact_data = ChallengeContactData(
                    // PATCH semantic, if not given do not change, if empty remove
                    email = if (email == null) Option.None else Option.Some(if (email != "") email else null),
                    phone = if (phone == null) Option.None else Option.Some(if (phone != "") phone else null), 
                ),
                cashout_payto_uri = Option.Some(cashout_payto_uri),
                debit_threshold = debit_threshold,
                min_cashout = when (val tmp = min_cashout) {
                    null -> Option.None
                    is Option.None -> Option.Some(null)
                    is Option.Some -> Option.Some(tmp.value)
                }
            )
            when (patchAccount(db, ctx, req, username, true, true)) {
                AccountPatchResult.Success -> 
                    logger.info("Account '$username' edited")
                AccountPatchResult.UnknownAccount -> 
                    throw Exception("Account '$username' not found")
                AccountPatchResult.MissingTanInfo -> 
                    throw Exception("missing info for tan channel ${req.tan_channel.get()}")
                AccountPatchResult.NonAdminName,
                    AccountPatchResult.NonAdminCashout,
                    AccountPatchResult.NonAdminDebtLimit,
                    AccountPatchResult.NonAdminMinCashout,
                    is AccountPatchResult.TanRequired  -> {
                        // Unreachable as we edit account as admin
                    }
            }
        }
    }
}

class CreateAccountOption: OptionGroup() {
    val username: String by option(
        "--username", "-u",
        help = "Account unique username"
    ).required()
    val password: String by option(
        "--password", "-p",
        help = "Account password used for authentication"
    ).prompt(requireConfirmation = true, hideInput = true)
    val name: String by option(
        help = "Legal name of the account owner"
    ).required()
    val is_public: Boolean by option(
        "--public",
        help = "Make this account visible to anyone"
    ).flag()
    val exchange: Boolean by option(
        help = "Make this account a taler exchange"
    ).flag()
    val email: String? by option(help = "E-Mail address used for TAN transmission")
    val phone: String? by option(help = "Phone number used for TAN transmission")
    val cashout_payto_uri: IbanPayto? by option(
        help = "Payto URI of a fiant account who receive cashout amount"
    ).convert { Payto.parse(it).expectIban() }
    val payto_uri: Payto? by option(
        help = "Payto URI of this account"
    ).convert { Payto.parse(it) }
    val debit_threshold: TalerAmount? by option(
        help = "Max debit allowed for this account"
    ).convert { TalerAmount(it) }
    val min_cashout: TalerAmount? by option(
        help = "Custom minimum cashout amount for this account"
    ).convert { TalerAmount(it) }

}

class CreateAccount : CliktCommand(
    "Create an account, returning the payto://-URI associated with it",
    name = "create-account"
) {
    private val common by CommonOption()
    private val json by argument().convert { Json.decodeFromString<RegisterAccountRequest>(it) }.optional()
    private val options by CreateAccountOption().cooccurring()
 
    override fun run() = cliCmd(logger, common.log) {
        // TODO support setting tan
        val cfg = talerConfig(common.config)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()

        Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            val req = json ?: options?.run {
                RegisterAccountRequest(
                    username = username,
                    password = password,
                    name = name,
                    is_public = is_public,
                    is_taler_exchange = exchange,
                    contact_data = ChallengeContactData(
                        email = Option.Some(email),
                        phone = Option.Some(phone), 
                    ),
                    cashout_payto_uri = cashout_payto_uri,
                    payto_uri = payto_uri,
                    debit_threshold = debit_threshold,
                    min_cashout = min_cashout
                ) 
            }
            req?.let {
                when (val result = createAccount(db, ctx, req, true)) {
                    AccountCreationResult.BonusBalanceInsufficient ->
                        throw Exception("Insufficient admin funds to grant bonus")
                    AccountCreationResult.LoginReuse ->
                        throw Exception("Account username reuse '${req.username}'")
                    AccountCreationResult.PayToReuse ->
                        throw Exception("Bank internalPayToUri reuse")
                    is AccountCreationResult.Success -> {
                        logger.info("Account '${req.username}' created")
                        println(result.payto)
                    }
                }
            }
        }
    }
}

class GC : CliktCommand(
    "Run garbage collection: abort expired operations and clean expired data",
    name = "gc"
) {
    private val common by CommonOption()
 
    override fun run() = cliCmd(logger, common.log) {
        val cfg = talerConfig(common.config)
        val ctx = cfg.loadBankConfig() 
        val dbCfg = cfg.loadDbConfig()

        Database(dbCfg, ctx.regionalCurrency, ctx.fiatCurrency).use { db ->
            logger.info("Run garbage collection")
            db.gc.collect(Instant.now(), ctx.gcAbortAfter, ctx.gcCleanAfter, ctx.gcDeleteAfter)
        }
    }
}

class LibeufinBankCommand : CliktCommand() {
    init {
        versionOption(getVersion())
        subcommands(ServeBank(), BankDbInit(), CreateAccount(), EditAccount(), ChangePw(), GC(), CliConfigCmd(BANK_CONFIG_SOURCE))
    }

    override fun run() = Unit
}

fun main(args: Array<String>) {
    LibeufinBankCommand().main(args)
}
