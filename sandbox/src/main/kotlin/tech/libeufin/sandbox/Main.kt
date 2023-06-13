/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

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

package tech.libeufin.sandbox

import UtilError
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import execThrowableOrTerminate
import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.util.date.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import startServer
import tech.libeufin.util.*
import java.math.BigDecimal
import java.net.URL
import java.security.interfaces.RSAPublicKey
import javax.xml.bind.JAXBContext
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")
const val PROTOCOL_VERSION_UNIFIED = "0:0:0" // Every protocol is still using the same version.
const val SANDBOX_DB_ENV_VAR_NAME = "LIBEUFIN_SANDBOX_DB_CONNECTION"
private val adminPassword: String? = System.getenv("LIBEUFIN_SANDBOX_ADMIN_PASSWORD")
var WITH_AUTH = true // Needed by helpers too, hence not making it private.

// Internal error type.
data class SandboxError(
    val statusCode: HttpStatusCode,
    val reason: String,
    val errorCode: LibeufinErrorCode? = null
) : Exception(reason)

// HTTP response error type.
data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

class DefaultExchange : CliktCommand("Set default Taler exchange for a demobank.") {
    init { context { helpFormatter = CliktHelpFormatter(showDefaultValues = true) } }
    private val exchangeBaseUrl by argument("EXCHANGE-BASEURL", "base URL of the default exchange")
    private val exchangePayto by argument("EXCHANGE-PAYTO", "default exchange's payto-address")
    private val demobank by option("--demobank", help = "Which demobank defaults to EXCHANGE").default("default")

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        execThrowableOrTerminate {
            dbCreateTables(dbConnString)
            transaction {
                val maybeDemobank: DemobankConfigEntity? = DemobankConfigEntity.find {
                    DemobankConfigsTable.name eq demobank
                }.firstOrNull()
                if (maybeDemobank == null) {
                    System.err.println("Error, demobank $demobank not found.")
                    exitProcess(1)
                }
                val config = maybeDemobank.config
                /**
                 * Iterating over the config object's field that hold the exchange
                 * base URL and Payto.  The iteration is only used to retrieve the
                 * correct names of the DB column 'configKey', because this is named
                 * after such fields.
                 */
                listOf(
                    Pair(config::suggestedExchangeBaseUrl, exchangeBaseUrl),
                    Pair(config::suggestedExchangePayto, exchangePayto)
                ).forEach {
                    val maybeConfigPair = DemobankConfigPairEntity.find {
                        DemobankConfigPairsTable.demobankName eq demobank and(
                                DemobankConfigPairsTable.configKey eq it.first.name)
                    }.firstOrNull()
                    /**
                     * The DB doesn't contain any column to hold the exchange URL
                     * or Payto, fail.  That should never happen, because the DB row
                     * are created _after_ the DemobankConfig object that _does_ contain
                     * such fields.
                     */
                    if (maybeConfigPair == null) {
                        System.err.println("Config key '${it.first.name}' for demobank '$demobank' not found in DB.")
                        exitProcess(1)
                    }
                    maybeConfigPair.configValue = it.second
                }
            }
        }
    }
}

class Config : CliktCommand("Insert one configuration (a.k.a. demobank) into the database.") {
    init { context { helpFormatter = CliktHelpFormatter(showDefaultValues = true) } }
    private val nameArgument by argument(
        "NAME", help = "Name of this configuration.  Currently, only 'default' is admitted."
    )
    private val showOption by option(
        "--show",
        help = "Only show values, other options will be ignored."
    ).flag("--no-show", default = false)
    // FIXME: This really should not be a global option!
    private val captchaUrlOption by option(
        "--captcha-url", help = "Needed for browser wallets."
    ).default("https://bank.demo.taler.net/")
    private val currencyOption by option("--currency").default("EUR")
    private val bankDebtLimitOption by option("--bank-debt-limit").int().default(1000000)
    private val usersDebtLimitOption by option("--users-debt-limit").int().default(1000)
    private val allowRegistrationsOption by option(
        "--with-registrations",
        help = "(defaults to allow registrations)" /* mentioning here as help message did not.  */
    ).flag("--without-registrations", default = true)
    private val withSignupBonusOption by option(
        "--with-signup-bonus",
        help = "Award new customers with 100 units of currency! (defaults to NO bonus)"
    ).flag("--without-signup-bonus", default = false)

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        if (nameArgument != "default") {
            System.err.println("This version admits only the 'default' name")
            exitProcess(1)
        }
        execThrowableOrTerminate {
            dbCreateTables(dbConnString)
            val maybeDemobank = transaction { getDemobank(nameArgument) }
            if (showOption) {
                if (maybeDemobank != null) {
                    printConfig(maybeDemobank)
                } else {
                    println("Demobank: $nameArgument not found.")
                    System.exit(1)
                }
                return@execThrowableOrTerminate
            }
            if (bankDebtLimitOption < 0 || usersDebtLimitOption < 0) {
                System.err.println("Debt numbers can't be negative.")
                exitProcess(1)
            }
            /*
               Warning if the CAPTCHA URL does not include the {wopid} placeholder.
               Not a reason to fail because the bank may be run WITHOUT providing Taler.
             */
            if (!hasWopidPlaceholder(captchaUrlOption))
                logger.warn("CAPTCHA URL doesn't have the WOPID placeholder." +
                        "  Taler withdrawals decrease usability")

            // The user asks to _set_ values, regardless of overriding or creating.
            val config = DemobankConfig(
                currency = currencyOption,
                bankDebtLimit = bankDebtLimitOption,
                usersDebtLimit = usersDebtLimitOption,
                allowRegistrations = allowRegistrationsOption,
                demobankName = nameArgument,
                withSignupBonus = withSignupBonusOption,
                captchaUrl = captchaUrlOption
            )
            /**
             * The demobank didn't exist.  Now:
             *   1, Store the config values in the database.
             *   2, Store the demobank name in the database.
             *   3, Create the admin bank account under this demobank.
             */
            if (maybeDemobank == null) {
                transaction {
                    insertConfigPairs(config)
                    val demoBank = DemobankConfigEntity.new { this.name = nameArgument }
                    BankAccountEntity.new {
                        iban = getIban()
                        label = "admin"
                        owner = "admin" // Not backed by an actual customer object.
                        // For now, the model assumes always one demobank
                        this.demoBank = demoBank
                    }
                }
            }
            // Demobank exists: update its config values in the database.
            else transaction { insertConfigPairs(config, override = true) }
        }
    }
}

/**
 * This command generates Camt53 statements - for all the bank accounts -
 * every time it gets run. The statements are only stored into the database.
 * The user should then query either via Ebics or via the JSON interface,
 * in order to retrieve their statements.
 */
class Camt053Tick : CliktCommand(
    "Make a new Camt.053 time tick; all the fresh transactions" +
            " will be inserted in a new Camt.053 report"
) {
    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        Database.connect(dbConnString, user = getCurrentUser())
        dbCreateTables(dbConnString)
        val newStatements = mutableMapOf<String, MutableList<XLibeufinBankTransaction>>()
        /**
         * For each bank account, extract the latest statement and
         * include all the later transactions in a new statement.
         * Build empty statement, if the account does not have any
         * transaction yet.
         */
        transaction {
            BankAccountEntity.all().forEach { accountIter ->
                // Give this account a entry in the final output.
                newStatements.putIfAbsent(accountIter.label, mutableListOf())
                val lastStatement = BankAccountStatementEntity.find {
                    BankAccountStatementsTable.bankAccount eq accountIter.id.value
                }.lastOrNull()
                val lastStatementTime = lastStatement?.creationTime ?: 0L
                BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.date.greater(lastStatementTime) and(
                            BankAccountTransactionsTable.account eq accountIter.id.value
                    )
                }.forEach {
                    newStatements[accountIter.label]?.add(
                        getHistoryElementFromTransactionRow(it)
                    ) ?: run {
                        logger.error("Array operation failed while building statements for account: ${accountIter.label}")
                        System.err.println("Fatal array error while building the statement, please report.")
                        exitProcess(1)
                    }
                }
                /**
                 * Resorting the closing (CLBD) balance of the last statement; will
                 * become the PRCD balance of the _new_ one.
                 */
                val lastBalance = getBalance(accountIter, withPending = false)
                val balanceClbd = getBalance(accountIter, withPending = true)
                val camtData = buildCamtString(
                    53,
                    accountIter.iban,
                    newStatements[accountIter.label]!!,
                    balanceClbd = balanceClbd,
                    balancePrcd = lastBalance,
                    currency = accountIter.demoBank.config.currency
                )
                BankAccountStatementEntity.new {
                    statementId = camtData.messageId
                    creationTime = getUTCnow().toInstant().epochSecond
                    xmlMessage = camtData.camtMessage
                    bankAccount = accountIter
                    this.balanceClbd = balanceClbd.toPlainString()
                }
            }
            BankAccountFreshTransactionsTable.deleteAll()
        }
    }
}

class MakeTransaction : CliktCommand("Wire-transfer money between Sandbox bank accounts") {
    init {
        context { helpFormatter = CliktHelpFormatter(showDefaultValues = true) }
    }
    private val creditAccount by option(help = "Label of the bank account receiving the payment").required()
    private val debitAccount by option(help = "Label of the bank account issuing the payment").required()
    private val demobankArg by option("--demobank", help = "Which Demobank books this transaction").default("default")
    private val amount by argument("AMOUNT", "Amount, in the CUR:X.Y format")
    private val subjectArg by argument("SUBJECT", "Payment's subject")

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        Database.connect(dbConnString, user = getCurrentUser())
        // Refuse to operate without a default demobank.
        val demobank = getDemobank("default")
        if (demobank == null) {
            System.err.println("Sandbox cannot operate without a 'default' demobank.")
            System.err.println("Please make one with the 'libeufin-sandbox config' command.")
            exitProcess(1)
        }
        try {
            wireTransfer(debitAccount, creditAccount, demobankArg, subjectArg, amount)
        } catch (e: SandboxError) {
            System.err.println(e.message)
            exitProcess(1)
        } catch (e: Exception) {
            System.err.println(e.message)
            exitProcess(1)
        }
    }
}

class ResetTables : CliktCommand("Drop all the tables from the database") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        execThrowableOrTerminate {
            dbDropTables(dbConnString)
            dbCreateTables(dbConnString)
        }
    }
}

class Serve : CliktCommand("Run sandbox HTTP server") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    private val auth by option(
        "--auth",
        help = "Disable authentication."
    ).flag("--no-auth", default = true)
    private val localhostOnly by option(
        "--localhost-only",
        help = "Bind only to localhost.  On all interfaces otherwise"
    ).flag("--no-localhost-only", default = true)
    private val ipv4Only by option(
        "--ipv4-only",
        help = "Bind only to ipv4"
    ).flag(default = false)
    private val logLevel by option(
        help = "Set the log level to: 'off', 'error', 'warn', 'info', 'debug', 'trace', 'all'"
    )
    private val port by option().int().default(5000)
    private val withUnixSocket by option(
        help = "Bind the Sandbox to the Unix domain socket at PATH.  Overrides" +
                " --port, when both are given", metavar = "PATH"
    )
    private val smsTan by option(help = "Command to send the TAN via SMS." +
            "  The command gets the TAN via STDIN and the phone number" +
            " as its first parameter"
    )
    private val emailTan by option(help = "Command to send the TAN via e-mail." +
            "  The command gets the TAN via STDIN and the e-mail address as its" +
            " first parameter.")
    override fun run() {
        WITH_AUTH = auth
        setLogLevel(logLevel)
        if (WITH_AUTH && adminPassword == null) {
            System.err.println(
                "Error: auth is enabled, but env " +
                        "LIBEUFIN_SANDBOX_ADMIN_PASSWORD is not."
                        + " (Option --no-auth exists for tests)"
            )
            exitProcess(1)
        }
        execThrowableOrTerminate {
            dbCreateTables(getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME))
        }
        // Refuse to operate without a 'default' demobank.
        val demobank = getDemobank("default")
        if (demobank == null) {
            System.err.println("Sandbox cannot operate without a 'default' demobank.")
            System.err.println("Please make one with the 'libeufin-sandbox config' command.")
            exitProcess(1)
        }
        if (withUnixSocket != null) {
            startServer(
                withUnixSocket!!,
                app = sandboxApp
            )
            exitProcess(0)
        }
        SMS_TAN_CMD = smsTan
        EMAIL_TAN_CMD = emailTan

        logger.info("Starting Sandbox on port ${this.port}")
        startServerWithIPv4Fallback(
            options = StartServerOptions(
                ipv4OnlyOpt = this.ipv4Only,
                localhostOnlyOpt = this.localhostOnly,
                portOpt = this.port
            ),
            app = sandboxApp
        )
    }
}

private fun getJsonFromDemobankConfig(fromDb: DemobankConfigEntity): Demobank {
    return Demobank(
        currency = fromDb.config.currency,
        userDebtLimit = fromDb.config.usersDebtLimit,
        bankDebtLimit = fromDb.config.bankDebtLimit,
        allowRegistrations = fromDb.config.allowRegistrations,
        name = fromDb.name
    )
}
fun findEbicsSubscriber(partnerID: String, userID: String, systemID: String?): EbicsSubscriberEntity? {
    return if (systemID == null) {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.partnerId eq partnerID) and (EbicsSubscribersTable.userId eq userID)
        }
    } else {
        EbicsSubscriberEntity.find {
            (EbicsSubscribersTable.partnerId eq partnerID) and
                    (EbicsSubscribersTable.userId eq userID) and
                    (EbicsSubscribersTable.systemId eq systemID)
        }
    }.firstOrNull()
}

data class SubscriberKeys(
    val authenticationPublicKey: RSAPublicKey,
    val encryptionPublicKey: RSAPublicKey,
    val signaturePublicKey: RSAPublicKey
)

data class EbicsHostPublicInfo(
    val hostID: String,
    val encryptionPublicKey: RSAPublicKey,
    val authenticationPublicKey: RSAPublicKey
)

data class BankAccountInfo(
    val label: String,
    val name: String,
    val iban: String,
    val bic: String,
)

inline fun <reified T> Document.toObject(): T {
    val jc = JAXBContext.newInstance(T::class.java)
    val m = jc.createUnmarshaller()
    return m.unmarshal(this, T::class.java).value
}

fun ensureNonNull(param: String?): String {
    return param ?: throw SandboxError(
        HttpStatusCode.BadRequest, "Bad ID given: $param"
    )
}

class SandboxCommand : CliktCommand(invokeWithoutSubcommand = true, printHelpOnEmptyArgs = true) {
    init { versionOption(getVersion()) }
    override fun run() = Unit
}

fun main(args: Array<String>) {
    SandboxCommand().subcommands(
        Serve(),
        ResetTables(),
        Config(),
        MakeTransaction(),
        Camt053Tick(),
        DefaultExchange()
    ).main(args)
}

fun setJsonHandler(ctx: ObjectMapper) {
    ctx.enable(SerializationFeature.INDENT_OUTPUT)
    ctx.setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
        indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
        indentObjectsWith(DefaultIndenter("  ", "\n"))
    })
    ctx.registerModule(
        KotlinModule.Builder()
            .withReflectionCacheSize(512)
            .configure(KotlinFeature.NullToEmptyCollection, false)
            .configure(KotlinFeature.NullToEmptyMap, false)
            .configure(KotlinFeature.NullIsSameAsDefault, enabled = true)
            .configure(KotlinFeature.SingletonSupport, enabled = false)
            .configure(KotlinFeature.StrictNullChecks, false)
            .build()
    )
}

private suspend fun getWithdrawal(call: ApplicationCall) {
    val op = getWithdrawalOperation(call.expectUriComponent("withdrawal_id"))
    if (!op.selectionDone && op.reservePub != null) throw internalServerError(
        "Unselected withdrawal has a reserve public key",
        LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE
    )
    call.respond(object {
        val amount = op.amount
        val aborted = op.aborted
        val confirmation_done = op.confirmationDone
        val selection_done = op.selectionDone
        val selected_reserve_pub = op.reservePub
        val selected_exchange_account = op.selectedExchangePayto
    })
}

private suspend fun confirmWithdrawal(call: ApplicationCall) {
    val withdrawalId = call.expectUriComponent("withdrawal_id")
    transaction {
        val wo = getWithdrawalOperation(withdrawalId)
        if (wo.aborted) throw SandboxError(
            HttpStatusCode.Conflict,
            "Cannot confirm an aborted withdrawal."
        )
        if (!wo.selectionDone) throw SandboxError(
            HttpStatusCode.UnprocessableEntity,
            "Cannot confirm a unselected withdrawal: " +
                    "specify exchange and reserve public key via Integration API first."
        )
        /**
         * The wallet chose not to select any exchange, use the default.
         */
        val demobank = ensureDemobank(call)
        if (wo.selectedExchangePayto == null) {
            wo.selectedExchangePayto = demobank.config.suggestedExchangePayto
        }
        val exchangeBankAccount = getBankAccountFromPayto(
            wo.selectedExchangePayto ?: throw internalServerError(
                "Cannot withdraw without an exchange."
            )
        )
        if (!wo.confirmationDone) {
            wireTransfer(
                debitAccount = wo.walletBankAccount,
                creditAccount = exchangeBankAccount,
                amount = wo.amount,
                subject = wo.reservePub ?: throw internalServerError(
                    "Cannot transfer funds without reserve public key."
                ),
                // provide the currency.
                demobank = ensureDemobank(call)
            )
            wo.confirmationDone = true
        }
        wo.confirmationDone
    }
    call.respond(object {})
}

private suspend fun abortWithdrawal(call: ApplicationCall) {
    val withdrawalId = call.expectUriComponent("withdrawal_id")
    val operation = getWithdrawalOperation(withdrawalId)
    if (operation.confirmationDone) throw conflict("Cannot abort paid withdrawal.")
    transaction { operation.aborted = true }
    call.respond(object {})
}

val sandboxApp: Application.() -> Unit = {
    install(CallLogging) {
        this.level = Level.DEBUG
        this.logger = tech.libeufin.sandbox.logger
        this.format { call ->
            "${call.response.status()}, ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(IgnoreTrailingSlash)
    install(ContentNegotiation) {
        register(ContentType.Text.Xml, XMLEbicsConverter())
        /**
         * Content type "text" must go to the XML parser
         * because Nexus can't set explicitly the Content-Type
         * (see https://github.com/ktorio/ktor/issues/1127) to
         * "xml" and the request made gets somehow assigned the
         * "text/plain" type:  */
        register(ContentType.Text.Plain, XMLEbicsConverter())
        jackson(contentType = ContentType.Application.Json) { setJsonHandler(this) }
        /**
         * Make jackson the default parser.  It runs also when
         * the Content-Type request header is missing. */
        jackson(contentType = ContentType.Any) { setJsonHandler(this) }
    }
    install(StatusPages) {
        // Bank's fault: it should check the operands.  Respond 500
        exception<ArithmeticException> { call, cause ->
            logger.error("Exception while handling '${call.request.uri}', ${cause.stackTraceToString()}")
            call.respond(
                HttpStatusCode.InternalServerError,
                SandboxErrorJson(
                    error = SandboxErrorDetailJson(
                        type = "sandbox-error",
                        description = cause.message ?: "Bank's error: arithmetic exception."
                    )
                )
            )
        }
        // Not necessarily the bank's fault.
        exception<SandboxError> { call, cause ->
            logger.error("Exception while handling '${call.request.uri}', ${cause.reason}")
            call.respond(
                cause.statusCode,
                SandboxErrorJson(
                    error = SandboxErrorDetailJson(
                        type = "sandbox-error",
                        description = cause.reason
                    )
                )
            )
        }
        // Not necessarily the bank's fault.
        exception<UtilError> { call, cause ->
            logger.error("Exception while handling '${call.request.uri}', ${cause.reason}")
            call.respond(
                cause.statusCode,
                SandboxErrorJson(
                    error = SandboxErrorDetailJson(
                        type = "util-error",
                        description = cause.reason
                    )
                )
            )
        }
        /**
         * Happens when a request fails to parse.  This branch triggers
         * only when a JSON request fails.  XML problems are caught within
         * the /ebicsweb handler and always ultimately rethrown as "EbicsRequestError",
         * hence they do not reach this branch.
         */
        exception<BadRequestException> { call, wrapper ->
            var rootCause = wrapper.cause
            while (rootCause?.cause != null) rootCause = rootCause.cause
            val errorMessage: String? = rootCause?.message ?: wrapper.message
            if (errorMessage == null) {
                logger.error("The bank didn't detect the cause of a bad request, fail.")
                logger.error(wrapper.stackTraceToString())
                throw SandboxError(
                    HttpStatusCode.InternalServerError,
                    "Did not find bad request details."
                )
            }
            logger.error(errorMessage)
            call.respond(
                HttpStatusCode.BadRequest,
                SandboxErrorJson(
                    error = SandboxErrorDetailJson(
                        type = "sandbox-error",
                        description = errorMessage
                    )
                )
            )
        }
        // Catch-all error, respond 500 because the bank didn't handle it.
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception while handling '${call.request.uri}'\n${cause.stackTraceToString()}")
            call.respond(
                HttpStatusCode.InternalServerError,
                SandboxErrorJson(
                    error = SandboxErrorDetailJson(
                        type = "sandbox-error",
                        description = cause.message ?: "Bank's error: unhandled exception."
                    )
                )
            )
        }
        exception<EbicsRequestError> { call, cause ->
            logger.error("Handling EbicsRequestError: ${cause.message}")
            respondEbicsTransfer(call, cause.errorText, cause.errorCode)
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val ac: ApplicationCall = call
        ac.attributes.put(WITH_AUTH_ATTRIBUTE_KEY, WITH_AUTH)
        if (WITH_AUTH) {
            if(adminPassword == null) {
                throw internalServerError(
                    "Sandbox has no admin password defined." +
                            " Please define LIBEUFIN_SANDBOX_ADMIN_PASSWORD in the environment, " +
                            "or launch with --no-auth."

                )
            }
            ac.attributes.put(ADMIN_PASSWORD_ATTRIBUTE_KEY, adminPassword)
        }
        return@intercept
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (this.call.response.status() == null) {
            call.respondText(
                "Not found (no route matched).\n",
                io.ktor.http.ContentType.Text.Plain,
                io.ktor.http.HttpStatusCode.NotFound
            )
            return@intercept finish()
        }
    }
    routing {
        get("/") {
            call.respondText(
                "Hello, this is the Sandbox\n",
                ContentType.Text.Plain
            )
        }
        // Respond with the last statement of the requesting account.
        // Query details in the body.
        post("/admin/payments/camt") {
            val username = call.request.basicAuth()
            val body = call.receive<CamtParams>()
            if (body.type != 53) throw SandboxError(
                HttpStatusCode.NotFound,
                "Only Camt.053 documents can be generated."
            )
            if (!allowOwnerOrAdmin(username, body.bankaccount))
                throw unauthorized("User '${username}' has no rights over" +
                        " bank account '${body.bankaccount}'")
            val camtMessage = transaction {
                val bankaccount = getBankAccountFromLabel(
                    body.bankaccount,
                    getDefaultDemobank()
                )
                BankAccountStatementEntity.find {
                    BankAccountStatementsTable.bankAccount eq bankaccount.id
                }.lastOrNull()?.xmlMessage ?: throw SandboxError(
                    HttpStatusCode.NotFound,
                    "Could not find any statements; please wait next tick"
                )
            }
            call.respondText(
                camtMessage, ContentType.Text.Xml, HttpStatusCode.OK
            )
            return@post
        }

        /**
         * Create a new bank account, no EBICS relation.  Okay
         * to let a user, since having a particular username allocates
         * already a bank account with such label.
         */
        post("/admin/bank-accounts/{label}") {
            val username = call.request.basicAuth()
            val body = call.receive<BankAccountInfo>()
            if (!allowOwnerOrAdmin(username, body.label))
                throw unauthorized("User '$username' has no rights over" +
                        " bank account '${body.label}'"
                )
            if (body.label == "admin" || body.label == "bank") throw forbidden(
                "Requested bank account label '${body.label}' not allowed."
            )
            transaction {
                val maybeBankAccount = BankAccountEntity.find {
                    BankAccountsTable.label eq body.label
                }.firstOrNull()
                if (maybeBankAccount != null)
                    throw conflict("Bank account '${body.label}' exist already")
                // owner username == bank account label
                val maybeCustomer = DemobankCustomerEntity.find {
                    DemobankCustomersTable.username eq body.label
                }.firstOrNull()
                if (maybeCustomer == null)
                    throw notFound("Customer '${body.label}' not found," +
                            " cannot own any bank account.")
                BankAccountEntity.new {
                    iban = body.iban
                    bic = body.bic
                    label = body.label
                    owner = body.label
                    demoBank = getDefaultDemobank()
                }
            }
            call.respond(object {})
            return@post
        }

        // Information about one bank account.
        get("/admin/bank-accounts/{label}") {
            val username = call.request.basicAuth()
            val label = call.expectUriComponent("label")
            val ret = transaction {
                val demobank = getDefaultDemobank()
                val bankAccount = getBankAccountFromLabel(label, demobank)
                if (!allowOwnerOrAdmin(username, label))
                    throw unauthorized("'${username}' has no rights over '$label'")
                val balance = getBalance(bankAccount, withPending = true)
                object {
                    val balance = "${bankAccount.demoBank.config.currency}:${balance}"
                    val iban = bankAccount.iban
                    val bic = bankAccount.bic
                    val label = bankAccount.label
                }
            }
            call.respond(ret)
            return@get
        }

        // Book one incoming payment for the requesting account.
        // The debtor is not required to have a customer account at this Sandbox.
        post("/admin/bank-accounts/{label}/simulate-incoming-transaction") {
            call.request.basicAuth(onlyAdmin = true)
            val body = call.receive<IncomingPaymentInfo>()
            val accountLabel = ensureNonNull(call.parameters["label"])
            val reqDebtorBic = body.debtorBic
            if (reqDebtorBic != null && !validateBic(reqDebtorBic)) {
                throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "invalid BIC"
                )
            }
            val amount = parseAmount(body.amount)
            transaction {
                val demobank = getDefaultDemobank()
                val account = getBankAccountFromLabel(
                    accountLabel, demobank
                )
                val randId = getRandomString(16)
                val customer = getCustomer(accountLabel)
                BankAccountTransactionEntity.new {
                    creditorIban = account.iban
                    creditorBic = account.bic
                    creditorName = customer.name ?: "Name not given."
                    debtorIban = body.debtorIban
                    debtorBic = reqDebtorBic
                    debtorName = body.debtorName
                    subject = body.subject
                    this.amount = amount.amount
                    date = getUTCnow().toInstant().toEpochMilli()
                    accountServicerReference = "sandbox-$randId"
                    this.account = account
                    direction = "CRDT"
                    this.demobank = demobank
                    this.currency = demobank.config.currency
                }
            }
            call.respond(object {})
        }
        // Associates a new bank account with an existing Ebics subscriber.
        post("/admin/ebics/bank-accounts") {
            call.request.basicAuth(onlyAdmin = true)
            val body = call.receive<EbicsBankAccountRequest>()
            val subscriber = getEbicsSubscriberFromDetails(
                body.subscriber.userID,
                body.subscriber.partnerID,
                body.subscriber.hostID
            )
            val res = insertNewAccount(
                username = body.label,
                /**
                 * This value makes only happy the account creator helper.
                 * Logic using this OBSOLETE HTTP handler would NOT expect
                 * to use this password anyway.  The reason is that such obsolete
                 * tests access their banking data always through the EBICS
                 * subscriber, needing therefore no HTTP basic password to operate.
                 */
                password = "not-used",
                iban = body.iban
            )
            transaction { subscriber.bankAccount = res.bankAccount }
            call.respond({})
            return@post
        }

        // Information about all the default demobank's bank accounts
        get("/admin/bank-accounts") {
            call.request.basicAuth(onlyAdmin = true)
            val accounts = mutableListOf<BankAccountInfo>()
            transaction {
                val demobank = getDefaultDemobank()
                // Finds all the accounts of this demobank.
                BankAccountEntity.find { BankAccountsTable.demoBank eq demobank.id }.forEach {
                    accounts.add(
                        BankAccountInfo(
                            label = it.label,
                            bic = it.bic,
                            iban = it.iban,
                            name = "Bank account owner's name"
                        )
                    )
                }
            }
            call.respond(accounts)
        }

        // Details of all the transactions of one bank account.
        get("/admin/bank-accounts/{label}/transactions") {
            val username = call.request.basicAuth()
            val ret = AccountTransactions()
            val accountLabel = ensureNonNull(call.parameters["label"])
            if (!allowOwnerOrAdmin(username, accountLabel))
                throw unauthorized("Requesting user '${username}'" +
                        " has no rights over bank account '${accountLabel}'"
            )
            transaction {
                val demobank = getDefaultDemobank()
                val account = getBankAccountFromLabel(accountLabel, demobank)
                BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.account eq account.id
                }.forEach {
                    ret.payments.add(
                        PaymentInfo(
                            accountLabel = account.label,
                            creditorIban = it.creditorIban,
                            accountServicerReference = it.accountServicerReference,
                            paymentInformationId = it.pmtInfId,
                            debtorIban = it.debtorIban,
                            subject = it.subject,
                            date = GMTDate(it.date).toHttpDate(),
                            amount = it.amount,
                            creditorBic = it.creditorBic,
                            creditorName = it.creditorName,
                            debtorBic = it.debtorBic,
                            debtorName = it.debtorName,
                            currency = it.currency,
                            creditDebitIndicator = when (it.direction) {
                                "CRDT" -> "credit"
                                "DBIT" -> "debit"
                                else -> throw Error("invalid direction")
                            }
                        )
                    )
                }
            }
            call.respond(ret)
        }
        /**
         * Generate one incoming and one outgoing transactions for
         * one bank account.  Counterparts do not need to have an account
         * at this Sandbox.
         */
        post("/admin/bank-accounts/{label}/generate-transactions") {
            call.request.basicAuth(onlyAdmin = true)
            transaction {
                val accountLabel = ensureNonNull(call.parameters["label"])
                val demobank = getDefaultDemobank()
                val account = getBankAccountFromLabel(accountLabel, demobank)
                val transactionReferenceCrdt = getRandomString(8)
                val transactionReferenceDbit = getRandomString(8)

                run {
                    val amount = kotlin.random.Random.nextLong(5, 25)
                    BankAccountTransactionEntity.new {
                        creditorIban = account.iban
                        creditorBic = account.bic
                        creditorName = "Creditor Name"
                        debtorIban = "DE64500105178797276788"
                        debtorBic = "DEUTDEBB101"
                        debtorName = "Max Mustermann"
                        subject = "sample transaction $transactionReferenceCrdt"
                        this.amount = amount.toString()
                        date = getUTCnow().toInstant().toEpochMilli()
                        accountServicerReference = transactionReferenceCrdt
                        this.account = account
                        direction = "CRDT"
                        this.demobank = demobank
                        currency = demobank.config.currency
                    }
                }

                run {
                    val amount = kotlin.random.Random.nextLong(5, 25)

                    BankAccountTransactionEntity.new {
                        debtorIban = account.iban
                        debtorBic = account.bic
                        debtorName = "Debitor Name"
                        creditorIban = "DE64500105178797276788"
                        creditorBic = "DEUTDEBB101"
                        creditorName = "Max Mustermann"
                        subject = "sample transaction $transactionReferenceDbit"
                        this.amount = amount.toString()
                        date = getUTCnow().toInstant().toEpochMilli()
                        accountServicerReference = transactionReferenceDbit
                        this.account = account
                        direction = "DBIT"
                        this.demobank = demobank
                        currency = demobank.config.currency
                    }
                }
            }
            call.respond(object {})
        }

        /**
         * Create a new EBICS subscriber without associating
         * a bank account to it.  Currently every registered
         * user is allowed to call this.
         */
        post("/admin/ebics/subscribers") {
            call.request.basicAuth(onlyAdmin = true)
            val body = call.receive<EbicsSubscriberObsoleteApi>()
            transaction {
                // Check the host ID exists.
                EbicsHostEntity.find {
                    EbicsHostsTable.hostID eq body.hostID
                }.firstOrNull() ?: throw notFound("Host ID ${body.hostID} not found.")
                // Check it exists first.
                val maybeSubscriber = EbicsSubscriberEntity.find {
                    EbicsSubscribersTable.userId eq body.userID and (
                            EbicsSubscribersTable.partnerId eq body.partnerID
                            ) and (EbicsSubscribersTable.systemId eq body.systemID) and
                            (EbicsSubscribersTable.hostId eq body.hostID)
                }.firstOrNull()
                if (maybeSubscriber != null) throw conflict("EBICS subscriber exists already")
                EbicsSubscriberEntity.new {
                    partnerId = body.partnerID
                    userId = body.userID
                    systemId = null
                    hostId = body.hostID
                    state = SubscriberState.NEW
                    nextOrderID = 1
                }
            }
            call.respondText(
                "Subscriber created.",
                ContentType.Text.Plain, HttpStatusCode.OK
            )
            return@post
        }

        // Shows details of all the EBICS subscribers of this Sandbox.
        get("/admin/ebics/subscribers") {
            call.request.basicAuth(onlyAdmin = true)
            val ret = AdminGetSubscribers()
            transaction {
                EbicsSubscriberEntity.all().forEach {
                    ret.subscribers.add(
                        EbicsSubscriberInfo(
                            userID = it.userId,
                            partnerID = it.partnerId,
                            hostID = it.hostId,
                            demobankAccountLabel = it.bankAccount?.label ?: "not associated yet"
                        )
                    )
                }
            }
            call.respond(ret)
            return@get
        }

        // Change keys used in the EBICS communications.
        post("/admin/ebics/hosts/{hostID}/rotate-keys") {
            call.request.basicAuth(onlyAdmin = true)
            val hostID: String = call.parameters["hostID"] ?: throw SandboxError(
                io.ktor.http.HttpStatusCode.BadRequest, "host ID missing in URL"
            )
            transaction {
                val host = EbicsHostEntity.find {
                    EbicsHostsTable.hostID eq hostID
                }.firstOrNull() ?: throw SandboxError(
                    HttpStatusCode.NotFound, "Host $hostID not found"
                )
                val pairA = CryptoUtil.generateRsaKeyPair(2048)
                val pairB = CryptoUtil.generateRsaKeyPair(2048)
                val pairC = CryptoUtil.generateRsaKeyPair(2048)
                host.authenticationPrivateKey = ExposedBlob(pairA.private.encoded)
                host.encryptionPrivateKey = ExposedBlob(pairB.private.encoded)
                host.signaturePrivateKey = ExposedBlob(pairC.private.encoded)
            }
            call.respondText(
                "Keys of '${hostID}' rotated.",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
            return@post
        }

        // Create a new EBICS host
        post("/admin/ebics/hosts") {
            call.request.basicAuth(onlyAdmin = true)
            val req = call.receive<EbicsHostCreateRequest>()
            val pairA = CryptoUtil.generateRsaKeyPair(2048)
            val pairB = CryptoUtil.generateRsaKeyPair(2048)
            val pairC = CryptoUtil.generateRsaKeyPair(2048)
            transaction {
                val maybeHost = EbicsHostEntity.find {
                    EbicsHostsTable.hostID eq req.hostID
                }.firstOrNull()
                if (maybeHost != null) {
                    logger.info("EBICS host '${req.hostID}' exists already, this request conflicts.")
                    throw conflict("EBICS host '${req.hostID}' exists already")
                }
                EbicsHostEntity.new {
                    this.ebicsVersion = req.ebicsVersion
                    this.hostId = req.hostID
                    this.authenticationPrivateKey = ExposedBlob(pairA.private.encoded)
                    this.encryptionPrivateKey = ExposedBlob(pairB.private.encoded)
                    this.signaturePrivateKey = ExposedBlob(pairC.private.encoded)
                }
            }
            call.respondText(
                "Host '${req.hostID}' created.",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
            return@post
        }

        // Show the names of all the Ebics hosts
        get("/admin/ebics/hosts") {
            call.request.basicAuth(onlyAdmin = true)
            val ebicsHosts = transaction {
                EbicsHostEntity.all().map { it.hostId }
            }
            call.respond(EbicsHostsResponse(ebicsHosts))
        }
        // Process one EBICS request
        post("/ebicsweb") {
            try { call.ebicsweb() }
            /**
             * The catch blocks try to extract a EBICS error message from the
             * exception type being handled.  NOT logging under each catch block
             * as ultimately the registered exception handler is expected to log. */
            catch (e: UtilError) {
                throw EbicsProcessingError("Serving EBICS threw unmanaged UtilError: ${e.reason}")
            }
            catch (e: SandboxError) {
                val errorInfo: String = e.message ?: e.stackTraceToString()
                logger.info(errorInfo)
                // Should translate to EBICS error code.
                when (e.errorCode) {
                    LibeufinErrorCode.LIBEUFIN_EC_INVALID_STATE -> throw EbicsProcessingError("Invalid bank state.")
                    LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE -> throw EbicsProcessingError("Inconsistent bank state.")
                    else -> throw EbicsProcessingError("Unknown Libeufin error code: ${e.errorCode}.")
                }
            }
            catch (e: EbicsNoDownloadDataAvailable) {
                respondEbicsTransfer(call, e.errorText, e.errorCode)
            }
            catch (e: EbicsRequestError) {
                /**
                 * Preventing the last catch-all block from handling
                 * a known error type.  Rethrowing here to let the top-level
                 * handler take action.
                 */
                throw e
            }
            catch (e: Exception) {
                logger.error(e.stackTraceToString())
                throw EbicsProcessingError(e.message)
            }
            return@post
        }

        /**
         * Create a new demobank instance with a particular currency,
         * debt limit and possibly other configuration
         * (could also be a CLI command for now)
         */
        post("/demobanks") {
            throw NotImplementedError("Feature only available at the libeufin-sandbox CLI")
        }

        get("/demobanks") {
            expectAdmin(call.request.basicAuth())
            val ret = object { val demoBanks = mutableListOf<Demobank>() }
            transaction {
                DemobankConfigEntity.all().forEach {
                    ret.demoBanks.add(getJsonFromDemobankConfig(it))
                }
            }
            call.respond(ret)
            return@get
        }

        get("/demobanks/{demobankid}") {
            val demobank = ensureDemobank(call)
            expectAdmin(call.request.basicAuth())
            call.respond(getJsonFromDemobankConfig(demobank))
            return@get
        }

        route("/demobanks/{demobankid}") {
            // NOTE: TWG assumes that username == bank account label.
            route("/taler-wire-gateway") {
                post("/{exchangeUsername}/admin/add-incoming") {
                    val username = call.expectUriComponent("exchangeUsername")
                    val usernameAuth = call.request.basicAuth()
                    if (username != usernameAuth)
                        throw forbidden("Bank account name and username differ: $username vs $usernameAuth")
                    logger.debug("TWG add-incoming passed authentication")
                    val body = try { call.receive<TWGAdminAddIncoming>() }
                    catch (e: Exception) {
                        logger.error("/admin/add-incoming failed at parsing the request body")
                        throw SandboxError(
                            HttpStatusCode.BadRequest,
                            "Invalid request"
                        )
                    }
                    transaction {
                        val demobank = ensureDemobank(call)
                        val bankAccountCredit = getBankAccountFromLabel(username, demobank)
                        if (bankAccountCredit.owner != username) throw forbidden(
                            "User '$username' cannot access bank account with label: $username."
                        )
                        val bankAccountDebit = getBankAccountFromPayto(body.debit_account)
                        logger.debug("TWG add-incoming about to wire transfer")
                        wireTransfer(
                            bankAccountDebit.label,
                            bankAccountCredit.label,
                            demobank.name,
                            body.reserve_pub,
                            body.amount
                        )
                        logger.debug("TWG add-incoming has wire transferred")
                    }
                    call.respond(object {})
                    return@post
                }
            }
            // Talk to wallets.
            route("/integration-api") {
                get("/config") {
                    val demobank = ensureDemobank(call)
                    call.respond(SandboxConfig(
                        name = "taler-bank-integration",
                        version = PROTOCOL_VERSION_UNIFIED,
                        currency = demobank.config.currency
                    ))
                    return@get
                }
                post("/withdrawal-operation/{wopid}") {
                    val arg = ensureNonNull(call.parameters["wopid"])
                    val withdrawalUuid = parseUuid(arg)
                    val body = call.receive<TalerWithdrawalSelection>()
                    val transferDone = transaction {
                        val wo = TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq withdrawalUuid
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound, "Withdrawal operation $withdrawalUuid not found."
                        )
                        if (wo.confirmationDone) {
                            return@transaction true
                        }
                        if (wo.selectionDone) {
                            if (body.reserve_pub != wo.reservePub) throw SandboxError(
                                HttpStatusCode.Conflict,
                                "Selecting a different reserve from the one already selected"
                            )
                            if (body.selected_exchange != wo.selectedExchangePayto) throw SandboxError(
                                HttpStatusCode.Conflict,
                                "Selecting a different exchange from the one already selected"
                            )
                            return@transaction false
                        }
                        // Flow here means never selected, hence must as well never be paid.
                        if (wo.confirmationDone) throw internalServerError(
                            "Withdrawal ${wo.wopid} knew NO exchange and reserve pub, " +
                                    "but is marked as paid!"
                        )
                        wo.reservePub = body.reserve_pub
                        wo.selectedExchangePayto = body.selected_exchange
                        wo.selectionDone = true
                        false
                    }
                    call.respond(object {
                        val transfer_done: Boolean = transferDone
                    })
                    return@post
                }
                get("/withdrawal-operation/{wopid}") {
                    val arg = ensureNonNull(call.parameters["wopid"])
                    val maybeWithdrawalUuid = parseUuid(arg)
                    val maybeWithdrawalOp = transaction {
                        TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq maybeWithdrawalUuid
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound,
                            "Withdrawal operation: $arg not found"
                        )
                    }
                    val demobank = ensureDemobank(call)
                    val captchaPage: String? = demobank.config.captchaUrl?.replace("{wopid}",arg)
                    if (captchaPage == null)
                        throw internalServerError("demobank ${demobank.name} lacks the CAPTCHA URL from the configuration.")
                    val ret = TalerWithdrawalStatus(
                        selection_done = maybeWithdrawalOp.selectionDone,
                        transfer_done = maybeWithdrawalOp.confirmationDone,
                        amount = maybeWithdrawalOp.amount,
                        suggested_exchange = demobank.config.suggestedExchangeBaseUrl,
                        aborted = maybeWithdrawalOp.aborted,
                        confirm_transfer_url = captchaPage
                    )
                    call.respond(ret)
                    return@get
                }
            }
            route("/circuit-api") {
                circuitApi(this)
            }
            // Talk to Web UI.
            route("/access-api") {
                post("/accounts/{account_name}/transactions") {
                    val username = call.request.basicAuth()
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(
                        call.expectUriComponent("account_name"),
                        demobank
                    )
                    // note: admin has no rights to create transactions on non-admin accounts.
                    val authGranted: Boolean = !WITH_AUTH
                    if (!authGranted && username != bankAccount.label)
                        throw unauthorized("Username '$username' has no rights over bank account ${bankAccount.label}")
                    val req = call.receive<XLibeufinBankPaytoReq>()
                    val payto = parsePayto(req.paytoUri)
                    val amount: String? = payto.amount ?: req.amount
                    if (amount == null) throw badRequest("Amount is missing")
                    /**
                     * The transaction block below lets the 'demoBank' field
                     * of 'bankAccount' be correctly accessed.  */
                    transaction {
                        wireTransfer(
                            debitAccount = bankAccount,
                            creditAccount = getBankAccountFromIban(payto.iban),
                            demobank = bankAccount.demoBank,
                            subject = payto.message ?: throw badRequest(
                                "'message' query parameter missing in Payto address"
                            ),
                            amount = amount,
                            pmtInfId = req.pmtInfId
                        )
                    }
                    call.respond(object {})
                    return@post
                }
                // Information about one withdrawal.
                get("/accounts/{account_name}/withdrawals/{withdrawal_id}") {
                    getWithdrawal(call)
                    return@get
                }
                // account-less style:
                get("/withdrawals/{withdrawal_id}") {
                    getWithdrawal(call)
                    return@get
                }
                // Create a new withdrawal operation.
                post("/accounts/{account_name}/withdrawals") {
                    var username = call.request.basicAuth()
                    val demobank = ensureDemobank(call)
                    /**
                     * Check here if the user has the right over the claimed bank account.  After
                     * this check, the withdrawal operation will be allowed only by providing its
                     * UID. */
                    val maybeOwnedAccount = getBankAccountFromLabel(
                        call.expectUriComponent("account_name"),
                        demobank
                    )
                    val authGranted = !WITH_AUTH // note: admin not allowed on non-admin accounts
                    if (!authGranted && maybeOwnedAccount.owner != username)
                        throw unauthorized("Customer '$username' has no rights over bank account '${maybeOwnedAccount.label}'")
                    val req = call.receive<WithdrawalRequest>()
                    // Check for currency consistency
                    val amount = parseAmount(req.amount)
                    if (amount.currency != demobank.config.currency)
                        throw badRequest("Currency ${amount.currency} differs from Demobank's: ${demobank.config.currency}")
                    // Check funds are sufficient.
                    if (
                        maybeDebit(
                            maybeOwnedAccount.label,
                            BigDecimal(amount.amount),
                            transaction { maybeOwnedAccount.demoBank.name }
                        )) {
                        logger.error("Account ${maybeOwnedAccount.label} would surpass debit threshold.  Not withdrawing")
                        throw SandboxError(HttpStatusCode.Forbidden, "Insufficient funds")
                    }
                    val wo: TalerWithdrawalEntity = transaction {
                        TalerWithdrawalEntity.new {
                        this.amount = req.amount
                        walletBankAccount = maybeOwnedAccount
                        }
                    }
                    val baseUrl = URL(call.request.getBaseUrl())
                    val withdrawUri = url {
                        protocol = URLProtocol(
                            name = "taler".plus(if (baseUrl.protocol.lowercase() == "http") "+http" else ""),
                            defaultPort = -1
                        )
                        host = "withdraw"
                        val pathSegments = mutableListOf(
                            /**
                             * encodes the hostname(+port) of the actual
                             * bank that will serve the withdrawal request.
                             */
                            baseUrl.host.plus(
                                if (baseUrl.port != -1)
                                    ":${baseUrl.port}"
                                else ""
                            )
                        )
                        /**
                         * Slashes can only be intermediate and single,
                         * any other combination results in badly formed URIs.
                         * The following loop ensure this for the current URI path.
                         * This might even come from X-Forwarded-Prefix.
                         */
                        baseUrl.path.split("/").forEach {
                            if (it.isNotEmpty()) pathSegments.add(it)
                        }
                        pathSegments.add("demobanks/${demobank.name}/integration-api/${wo.wopid}")
                        this.appendPathSegments(pathSegments)
                    }
                    call.respond(object {
                        val withdrawal_id = wo.wopid.toString()
                        val taler_withdraw_uri = withdrawUri
                    })
                    return@post
                }
                // Confirm a withdrawal: no basic auth, because the ID should be unguessable.
                post("/accounts/{account_name}/withdrawals/{withdrawal_id}/confirm") {
                    confirmWithdrawal(call)
                    return@post
                }
                // account-less style:
                post("/withdrawals/{withdrawal_id}/confirm") {
                    confirmWithdrawal(call)
                    return@post
                }
                // Aborting withdrawals:
                post("/accounts/{account_name}/withdrawals/{withdrawal_id}/abort") {
                    abortWithdrawal(call)
                    return@post
                }
                // account-less style:
                post("/withdrawals/{withdrawal_id}/abort") {
                    abortWithdrawal(call)
                    return@post
                }
                // Bank account basic information.
                get("/accounts/{account_name}") {
                    val username = call.request.basicAuth()
                    val accountAccessed = call.expectUriComponent("account_name")
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(accountAccessed, demobank)
                    val authGranted = !WITH_AUTH || bankAccount.isPublic || username == "admin"
                    if (!authGranted && bankAccount.owner != username)
                        throw forbidden("Customer '$username' cannot access bank account '$accountAccessed'")
                    val balance = getBalance(bankAccount, withPending = true)
                    call.respond(object {
                        val balance = object {
                            val amount = "${demobank.config.currency}:${balance.abs(). toPlainString()}"
                            val credit_debit_indicator = if (balance < BigDecimal.ZERO) "debit" else "credit"
                        }
                        val paytoUri = buildIbanPaytoUri(
                            iban = bankAccount.iban,
                            bic = bankAccount.bic,
                            // username 'null' should only happen when auth is disabled.
                            receiverName = getPersonNameFromCustomer(bankAccount.owner)
                        )
                        val iban = bankAccount.iban
                        // The Elvis operator helps the --no-auth case,
                        // where username would be empty
                        val debitThreshold = getMaxDebitForUser(
                            username = username ?: "admin",
                            demobankName = demobank.name
                        ).toString()
                    })
                    return@get
                }
                get("/accounts/{account_name}/transactions/{tId}") {
                    val username = call.request.basicAuth()
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(
                        call.expectUriComponent("account_name"),
                        demobank
                    )
                    val authGranted: Boolean = bankAccount.isPublic || !WITH_AUTH || username == "admin"
                    if (!authGranted && username != bankAccount.owner)
                        throw forbidden("Cannot access bank account ${bankAccount.label}")
                    val tId = call.parameters["tId"] ?: throw badRequest("URI didn't contain the transaction ID")
                    val tx: BankAccountTransactionEntity? = transaction {
                        BankAccountTransactionEntity.find {
                            BankAccountTransactionsTable.accountServicerReference eq tId
                        }.firstOrNull()
                    }
                    if (tx == null) throw notFound("Transaction $tId wasn't found")
                    call.respond(getHistoryElementFromTransactionRow(tx))
                    return@get
                }
                get("/accounts/{account_name}/transactions") {
                    val username = call.request.basicAuth()
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(
                        call.expectUriComponent("account_name"),
                        demobank
                    )
                    val authGranted: Boolean = bankAccount.isPublic || !WITH_AUTH || username == "admin"
                    if (!authGranted && bankAccount.owner != username)
                        throw forbidden("Cannot access bank account ${bankAccount.label}")
                    // Paging values.
                    val page: Int = expectInt(call.request.queryParameters["page"] ?: "1")
                    if (page < 1) throw badRequest("'page' param is less than 1")
                    val size: Int = expectInt(call.request.queryParameters["size"] ?: "5")
                    if (size < 1) throw badRequest("'size' param is less than 1")
                    // Time range filter values
                    val fromMs = expectLong(call.request.queryParameters["from_ms"] ?: "0")
                    if (fromMs < 0) throw badRequest("'from_ms' param is less than 0")
                    val untilMs = expectLong(call.request.queryParameters["until_ms"] ?: Long.MAX_VALUE.toString())
                    if (untilMs < 0) throw badRequest("'until_ms' param is less than 0")
                    val longPollMs: Long? = call.maybeLong("long_poll_ms")
                    // LISTEN, if Postgres.
                    val listenHandle = if (isPostgres() && longPollMs != null) {
                        val channelName = buildChannelName(
                            NotificationsChannelDomains.LIBEUFIN_REGIO_TX,
                            call.expectUriComponent("account_name")
                        )
                        val listenHandle = PostgresListenHandle(channelName)
                        // Can't LISTEN on the same DB TX that checks for data, as Exposed
                        // closes that connection and the notification getter would fail.
                        // Can't invoke the notification getter in the same DB TX either,
                        // as it would block the DB.
                        listenHandle.postgresListen()
                        listenHandle
                    } else null
                    val historyParams = HistoryParams(
                        pageNumber = page,
                        pageSize = size,
                        bankAccount = bankAccount,
                        fromMs = fromMs,
                        untilMs = untilMs
                    )
                    var ret: List<XLibeufinBankTransaction> = transaction {
                        extractTxHistory(historyParams)
                    }
                    // Data was found already, UNLISTEN and respond.
                    if (listenHandle != null && ret.isNotEmpty()) {
                        listenHandle.postgresUnlisten()
                        call.respond(object {val transactions = ret})
                        return@get
                    }
                    // No data was found, sleep until the timeout or getting woken up.
                    // Third condition only silences the compiler.
                    if (listenHandle != null && ret.isEmpty() && longPollMs != null) {
                        val notificationArrived = listenHandle.waitOnIODispatchers(longPollMs)
                        // Only if the awaited event fired, query again the DB.
                        if (notificationArrived)
                        {
                            ret = transaction {
                                // Refreshing to update the index to the very last transaction.
                                historyParams.bankAccount.refresh()
                                extractTxHistory(historyParams)
                            }
                        }
                    }
                    call.respond(object {val transactions = ret})
                    return@get
                }
                get("/public-accounts") {
                    val demobank = ensureDemobank(call)
                    val ret = object {
                        val publicAccounts = mutableListOf<PublicAccountInfo>()
                    }
                    transaction {
                        BankAccountEntity.find {
                            BankAccountsTable.isPublic eq true and(
                                    BankAccountsTable.demoBank eq demobank.id
                            )
                        }.forEach {
                            val balanceIter = getBalance(
                                it,
                                withPending = true,
                            )
                            ret.publicAccounts.add(
                                PublicAccountInfo(
                                    balance = "${demobank.config.currency}:$balanceIter",
                                    iban = it.iban,
                                    accountLabel = it.label
                                )
                            )
                        }
                    }
                    call.respond(ret)
                    return@get
                }
                delete("accounts/{account_name}") {
                    val username = call.request.basicAuth()
                    val demobank = ensureDemobank(call)
                    val authGranted = !WITH_AUTH || username == "admin"
                    val bankAccountLabel = call.expectUriComponent("account_name")
                    /**
                     * This helper fails if the demobank that is mentioned in the URI
                     * is not hosting the account to be deleted.
                     */
                    val bankAccount = getBankAccountFromLabel(
                        bankAccountLabel,
                        demobank
                    )
                    if (!authGranted && username != bankAccount.owner)
                        throw unauthorized("User '$username' has no rights to delete bank account '$bankAccountLabel'")
                    transaction {
                        val customerAccount = getCustomer(bankAccount.owner)
                        bankAccount.delete()
                        customerAccount.delete()
                    }
                    call.respond(object {})
                    return@delete
                }
                // Keeping the prefix "testing" not to break tests.
                post("/testing/register") {
                    // Check demobank was created.
                    val demobank = ensureDemobank(call)
                    if (!demobank.config.allowRegistrations) {
                        throw SandboxError(
                            HttpStatusCode.UnprocessableEntity,
                            "The bank doesn't allow new registrations at the moment."
                        )
                    }
                    val req = call.receive<CustomerRegistration>()
                    val newAccount = insertNewAccount(
                        req.username,
                        req.password,
                        name = req.name,
                        iban = req.iban,
                        demobank = demobank.name,
                        isPublic = req.isPublic
                    )
                    val balance = getBalance(newAccount.bankAccount, withPending = true)
                    call.respond(object {
                        val balance = getBalanceForJson(balance, demobank.config.currency)
                        val paytoUri = buildIbanPaytoUri(
                            iban = newAccount.bankAccount.iban,
                            bic = newAccount.bankAccount.bic,
                            receiverName = getPersonNameFromCustomer(req.username)
                        )
                        val iban = newAccount.bankAccount.iban
                        val debitThreshold = getMaxDebitForUser(
                            req.username,
                            demobank.name
                        ).toString()
                    })
                    return@post
                }
            }
            route("/ebics") {
                /**
                 * Associate an existing bank account to one EBICS subscriber.
                 * If the subscriber is not found, it is created.
                 */
                post("/subscribers") {
                    // Only the admin can create Ebics subscribers.
                    val user = call.request.basicAuth()
                    if (WITH_AUTH && (user != "admin")) throw forbidden("Only the Administrator can create Ebics subscribers.")
                    val body = call.receive<EbicsSubscriberInfo>()
                    // Create or get the Ebics subscriber that is found.
                    transaction {
                        // Check that host ID exists
                        EbicsHostEntity.find {
                            EbicsHostsTable.hostID eq body.hostID
                        }.firstOrNull() ?: throw notFound("Host ID ${body.hostID} not found.")
                        val subscriber: EbicsSubscriberEntity = EbicsSubscriberEntity.find {
                            (EbicsSubscribersTable.partnerId eq body.partnerID).and(
                                EbicsSubscribersTable.userId eq body.userID
                            ).and(EbicsSubscribersTable.hostId eq body.hostID)
                        }.firstOrNull() ?: EbicsSubscriberEntity.new {
                            partnerId = body.partnerID
                            userId = body.userID
                            systemId = null
                            hostId = body.hostID
                            state = SubscriberState.NEW
                            nextOrderID = 1
                        }
                        val bankAccount = getBankAccountFromLabel(
                            body.demobankAccountLabel,
                            ensureDemobank(call)
                        )
                        subscriber.bankAccount = bankAccount
                    }
                    call.respond(object {})
                    return@post
                }
            }
        }
    }
}
