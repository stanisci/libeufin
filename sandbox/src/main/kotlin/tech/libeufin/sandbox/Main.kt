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


/*
General thoughts:

 - since sandbox will run on the public internet for the demobank, all endpoints except
   explicitly public ones should use authentication (basic auth)
 - the authentication should be *very* simple and *not* be part of the database state.
   instead, a LIBEUFIN_SANDBOX_ADMIN_TOKEN environment variable will be used to
   set the authentication.

 - All sandbox will require the ADMIN_TOKEN, except:
   - the /ebicsweb endpoint, because EBICS handles authentication here
     (EBICS subscribers are checked)
   - the /demobank(/...) endpoints (except registration and public accounts),
     because authentication is handled by checking the demobank user credentials
 */

package tech.libeufin.sandbox

import UtilError
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import execThrowableOrTerminate
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import io.ktor.util.date.*
import kotlinx.coroutines.newSingleThreadContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import startServer
import tech.libeufin.util.*
import java.io.*
import java.math.BigDecimal
import java.net.BindException
import java.net.URL
import java.security.interfaces.RSAPublicKey
import javax.xml.bind.JAXBContext
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")
private val currencyEnv: String? = System.getenv("LIBEUFIN_SANDBOX_CURRENCY")
const val SANDBOX_DB_ENV_VAR_NAME = "LIBEUFIN_SANDBOX_DB_CONNECTION"
private val adminPassword: String? = System.getenv("LIBEUFIN_SANDBOX_ADMIN_PASSWORD")
var WITH_AUTH = true // Needed by helpers too, hence not making it private.

data class SandboxError(
    val statusCode: HttpStatusCode,
    val reason: String,
    val errorCode: LibeufinErrorCode? = null
) : Exception()

data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

class DefaultExchange : CliktCommand("Set default Taler exchange for a demobank.") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
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
                    println("Error, demobank ${demobank} not found.")
                    exitProcess(1)
                }
                maybeDemobank.suggestedExchangeBaseUrl = exchangeBaseUrl
                maybeDemobank.suggestedExchangePayto = exchangePayto
            }
        }
    }
}

class Config : CliktCommand("Insert one configuration into the database") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }

    private val nameOption by argument(
        "NAME", help = "Name of this configuration"
    )
    private val currencyOption by option("--currency").default("EUR")
    private val bankDebtLimitOption by option("--bank-debt-limit").int().default(1000000)
    private val usersDebtLimitOption by option("--users-debt-limit").int().default(1000)
    private val allowRegistrationsOption by option(
        "--allow-registrations",
        help = "(default: true)" /* mentioning here as help message did not.  */
    ).flag(default = true)

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        execThrowableOrTerminate {
            dbCreateTables(dbConnString)
            transaction {
                val checkExist = DemobankConfigEntity.find {
                    DemobankConfigsTable.name eq nameOption
                }.firstOrNull()
                if (checkExist != null) {
                    println("Error, demobank ${nameOption} exists already, not overriding it.")
                    exitProcess(1)
                }
                val demoBank = DemobankConfigEntity.new {
                    currency = currencyOption
                    bankDebtLimit = bankDebtLimitOption
                    usersDebtLimit = usersDebtLimitOption
                    allowRegistrations = allowRegistrationsOption
                    name = nameOption
                }
                BankAccountEntity.new {
                    iban = getIban()
                    label = "bank" // used by the wire helper
                    owner = "bank" // used by the person name finder
                    // For now, the model assumes always one demobank
                    this.demoBank = demoBank
                }
            }
        }
    }
}

/**
 * This command generates Camt53 statements - for all the bank accounts -
 * every time it gets run. The statements are only stored them into the database.
 * The user should then query either via Ebics or via the JSON interface,
 * in order to retrieve their statements.
 */
class Camt053Tick : CliktCommand(
    "Make a new Camt.053 time tick; all the fresh transactions" +
            " will be inserted in a new Camt.053 report"
) {
    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        Database.connect(dbConnString)
        dbCreateTables(dbConnString)
        transaction {
            BankAccountEntity.all().forEach { accountIter ->
                /**
                 * Map of 'account name' -> fresh history
                 */
                val histories = mutableMapOf<
                        String,
                        MutableList<RawPayment>>()
                BankAccountFreshTransactionEntity.all().forEach {
                    val bankAccountLabel = it.transactionRef.account.label
                    histories.putIfAbsent(bankAccountLabel, mutableListOf())
                    val historyIter = histories[bankAccountLabel]
                    historyIter?.add(getHistoryElementFromTransactionRow(it))
                }
                /**
                 * Resorting the closing (CLBD) balance of the last statement; will
                 * become the PRCD balance of the _new_ one.
                 */
                val lastBalance = getLastBalance(accountIter)
                val balanceClbd = balanceForAccount(
                    history = histories[accountIter.label] ?: mutableListOf(),
                    baseBalance = lastBalance
                )
                val camtData = buildCamtString(
                    53,
                    accountIter.iban,
                    histories[accountIter.label] ?: mutableListOf(),
                    balanceClbd = balanceClbd,
                    balancePrcd = lastBalance
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
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }

    private val creditAccount by option(help = "Label of the bank account receiving the payment").required()
    private val debitAccount by option(help = "Label of the bank account issuing the payment").required()
    private val demobankArg by option("--demobank", help = "Which Demobank books this transaction").default("default")
    private val amount by argument("AMOUNT", "Amount, in the CUR:X.Y format")
    private val subjectArg by argument("SUBJECT", "Payment's subject")

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        Database.connect(dbConnString)
        /**
         * The function below create a default demobank
         * if the 'config' command was never run before this point.
         *
         * This helps porting current tests to the "demobank model".
         */
        maybeCreateDefaultDemobank()
        try {
            wireTransfer(debitAccount, creditAccount, demobankArg, subjectArg, amount)
        } catch (e: SandboxError) {
            print(e.message)
            exitProcess(1)
        } catch (e: Exception) {
            // Here, Sandbox is in a highly unstable state.
            println(e)
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
    private val logLevel by option()
    private val port by option().int().default(5000)
    private val withUnixSocket by option(
        help = "Bind the Sandbox to the Unix domain socket at PATH.  Overrides" +
                " --port, when both are given", metavar = "PATH"
    )

    override fun run() {
        WITH_AUTH = auth
        setLogLevel(logLevel)
        if (WITH_AUTH && adminPassword == null) {
            println("Error: auth is enabled, but env LIBEUFIN_SANDBOX_ADMIN_PASSWORD is not."
            + " (Option --no-auth exists for tests)")
            exitProcess(1)
        }
        execThrowableOrTerminate { dbCreateTables(getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)) }
        maybeCreateDefaultDemobank()
        if (withUnixSocket != null) {
            startServer(
                withUnixSocket ?: throw Exception("Could not use the Unix domain socket path value!"),
                app = sandboxApp
            )
            exitProcess(0)
        }
        serverMain(port)
    }
}

private fun getJsonFromDemobankConfig(fromDb: DemobankConfigEntity): Demobank {
    return Demobank(
        currency = fromDb.currency,
        userDebtLimit = fromDb.usersDebtLimit,
        bankDebtLimit = fromDb.bankDebtLimit,
        allowRegistrations = fromDb.allowRegistrations,
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
    init {
        versionOption(getVersion())
    }

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

suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    try {
        return this.receive()
    } catch (e: MissingKotlinParameterException) {
        throw SandboxError(HttpStatusCode.BadRequest, "Missing value for ${e.pathReference}")
    } catch (e: MismatchedInputException) {
        // Note: POSTing "[]" gets here but e.pathReference is blank.
        throw SandboxError(HttpStatusCode.BadRequest, "Invalid value for '${e.pathReference}'")
    } catch (e: JsonParseException) {
        throw SandboxError(HttpStatusCode.BadRequest, "Invalid JSON")
    }
}

val singleThreadContext = newSingleThreadContext("DB")
val sandboxApp: Application.() -> Unit = {
    install(CallLogging) {
        this.level = org.slf4j.event.Level.DEBUG
    }
    install(CORS) {
        anyHost()
        header(HttpHeaders.Authorization)
        header(HttpHeaders.ContentType)
        method(HttpMethod.Options)
        logger.info("Enabling CORS (assuming no endpoint uses cookies).")
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
        /**
         * Make jackson the default parser.  It runs also when
         * the Content-Type request header is missing. */
        jackson(contentType = ContentType.Any) {
            enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
                indentObjectsWith(DefaultIndenter("  ", "\n"))
            })
            registerModule(KotlinModule(nullisSameAsDefault = true))
        }
    }
    install(StatusPages) {
        exception<ArithmeticException> { cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause)
            call.respondText(
                "Invalid arithmetic attempted.",
                ContentType.Text.Plain,
                // here is always the bank's fault, as it should always check
                // the operands.
                HttpStatusCode.InternalServerError
            )
        }
        exception<SandboxError> { cause ->
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
        exception<UtilError> { cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause)
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
        exception<EbicsRequestError> { e ->
            logger.debug("Handling EbicsRequestError: $e")
            respondEbicsTransfer(call, e.errorText, e.errorCode)
        }
        exception<Throwable> { cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause)
            call.respondText(
                "Internal server error.",
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError
            )
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
            ac.attributes.put(
                ADMIN_PASSWORD_ATTRIBUTE_KEY,
                adminPassword
            )
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
            call.request.basicAuth()
            val body = call.receiveJson<CamtParams>()
            if (body.type != 53) throw SandboxError(
                HttpStatusCode.NotFound,
                "Only Camt.053 documents can be generated."
            )
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

        // create a new bank account, no EBICS relation.
        post("/admin/bank-accounts/{label}") {
            val username = call.request.basicAuth()
            val body = call.receiveJson<BankAccountInfo>()
            transaction {
                BankAccountEntity.new {
                    iban = body.iban
                    bic = body.bic
                    label = body.label
                    owner = username ?: "admin" // allows
                    demoBank = getDefaultDemobank()
                }
            }
            call.respond(object {})
            return@post
        }

        // Information about one bank account.
        get("/admin/bank-accounts/{label}") {
            call.request.basicAuth()
            val label = call.getUriComponent("label")
            val ret = transaction {
                val demobank = getDefaultDemobank()
                val bankAccount = getBankAccountFromLabel(label, demobank)
                val balance = balanceForAccount(bankAccount)
                object {
                    val balance = "${bankAccount.demoBank.currency}:${balance}"
                    val iban = bankAccount.iban
                    val bic = bankAccount.bic
                    val label = bankAccount.label
                }
            }
            call.respond(ret)
            return@get
        }

        // Book one incoming payment for the requesting account.
        // The debtor is not required to have an account at this Sandbox.
        post("/admin/bank-accounts/{label}/simulate-incoming-transaction") {
            call.request.basicAuth()
            val body = call.receiveJson<IncomingPaymentInfo>()
            // FIXME: generate nicer UUID!
            val accountLabel = ensureNonNull(call.parameters["label"])
            if (!validatePlainAmount(body.amount)) {
                throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "invalid amount (should be plain amount without currency)"
                )
            }
            val reqDebtorBic = body.debtorBic
            if (reqDebtorBic != null && !validateBic(reqDebtorBic)) {
                throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "invalid BIC"
                )
            }
            transaction {
                val demobank = getDefaultDemobank()
                val account = getBankAccountFromLabel(
                    accountLabel, demobank
                )
                val randId = getRandomString(16)
                BankAccountTransactionEntity.new {
                    creditorIban = account.iban
                    creditorBic = account.bic
                    creditorName = "Creditor Name" // FIXME: Waits to get this value from the DemobankCustomer type.
                    debtorIban = body.debtorIban
                    debtorBic = reqDebtorBic
                    debtorName = body.debtorName
                    subject = body.subject
                    amount = body.amount
                    date = getUTCnow().toInstant().toEpochMilli()
                    accountServicerReference = "sandbox-$randId"
                    this.account = account
                    direction = "CRDT"
                    this.demobank = demobank
                    currency = demobank.currency
                }
            }
            call.respond(object {})
        }
        // Associates a new bank account with an existing Ebics subscriber.
        post("/admin/ebics/bank-accounts") {
            val username = call.request.basicAuth()
            val body = call.receiveJson<EbicsBankAccountRequest>()
            if (!validateBic(body.bic)) {
                throw SandboxError(HttpStatusCode.BadRequest, "invalid BIC (${body.bic})")
            }
            transaction {
                val subscriber = getEbicsSubscriberFromDetails(
                    body.subscriber.userID,
                    body.subscriber.partnerID,
                    body.subscriber.hostID
                )
                val demobank = getDefaultDemobank()

                /**
                 * Checking that the default demobank doesn't have already the
                 * requested IBAN and bank account label.
                 */
                val check = BankAccountEntity.find {
                    BankAccountsTable.iban eq body.iban or (
                            (BankAccountsTable.label eq body.label) and (
                                    BankAccountsTable.demoBank eq demobank.id
                                    )
                            )
                }.count()
                if (check > 0) throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "Either IBAN or account label were already taken; please choose fresh ones"
                )
                subscriber.bankAccount = BankAccountEntity.new {
                    iban = body.iban
                    bic = body.bic
                    label = body.label
                    owner = username ?: "admin"
                    demoBank = demobank
                }
            }
            call.respondText("Bank account created")
            return@post
        }

        // Information about all the default demobank's bank accounts
        get("/admin/bank-accounts") {
            call.request.basicAuth()
            val accounts = mutableListOf<BankAccountInfo>()
            transaction {
                val demobank = getDefaultDemobank()
                BankAccountEntity.find {
                    BankAccountsTable.demoBank eq demobank.id
                }.forEach {
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
            call.request.basicAuth()
            val ret = AccountTransactions()
            transaction {
                val accountLabel = ensureNonNull(call.parameters["label"])
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
                                // FIXME: We need to modify the transactions table to have an actual
                                // account servicer reference here.
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
            }
            call.respond(ret)
        }

        // Generate one incoming and one outgoing transactions for
        // one bank account.  Counterparts do not need to have an account
        // at this Sandbox.
        post("/admin/bank-accounts/{label}/generate-transactions") {
            call.request.basicAuth()
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
                        currency = demobank.currency
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
                        currency = demobank.currency
                    }
                }
            }
            call.respond(object {})
        }


        post("/admin/ebics/subscribers") {
            call.request.basicAuth()
            val body = call.receiveJson<EbicsSubscriberObsoleteApi>()
            transaction {
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
            call.request.basicAuth()
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
            call.request.basicAuth()
            val hostID: String = call.parameters["hostID"] ?: throw SandboxError(
                io.ktor.http.HttpStatusCode.BadRequest, "host ID missing in URL"
            )
            transaction {
                val host = tech.libeufin.sandbox.EbicsHostEntity.find {
                    tech.libeufin.sandbox.EbicsHostsTable.hostID eq hostID
                }.firstOrNull() ?: throw SandboxError(
                    io.ktor.http.HttpStatusCode.NotFound, "Host $hostID not found"
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
            call.request.basicAuth()
            val req = call.receiveJson<EbicsHostCreateRequest>()
            val pairA = CryptoUtil.generateRsaKeyPair(2048)
            val pairB = CryptoUtil.generateRsaKeyPair(2048)
            val pairC = CryptoUtil.generateRsaKeyPair(2048)
            transaction {
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
            call.request.basicAuth()
            val ebicsHosts = transaction {
                EbicsHostEntity.all().map { it.hostId }
            }
            call.respond(EbicsHostsResponse(ebicsHosts))
        }

        // Process one EBICS request
        post("/ebicsweb") {
            try {
                call.ebicsweb()
            }
            /**
             * The catch blocks below act as translators from
             * generic error types to EBICS-formatted responses.
             */
            catch (e: UtilError) {
                logger.error(e)
                throw EbicsProcessingError("Serving EBICS threw unmanaged UtilError: ${e.reason}")
            }
            catch (e: SandboxError) {
                logger.error(e)
                // Should translate to EBICS error code.
                when (e.errorCode) {
                    LibeufinErrorCode.LIBEUFIN_EC_INVALID_STATE -> throw EbicsProcessingError("Invalid bank state.")
                    LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE -> throw EbicsProcessingError("Inconsistent bank state.")
                    else -> throw EbicsProcessingError("Unknown LibEuFin error code: ${e.errorCode}.")
                }
            }
            catch (e: EbicsNoDownloadDataAvailable) {
                respondEbicsTransfer(call, e.errorText, e.errorCode)
            }
            catch (e: EbicsRequestError) {
                logger.error(e)
                // Preventing the last catch-all block
                // from capturing a known type.
                throw e
            }
            catch (e: Exception) {
                logger.error(e)
                throw EbicsProcessingError("Unmanaged error: $e")
            }
            return@post
        }

        // Create a new demobank instance with a particular currency,
        // debt limit and possibly other configuration
        // (could also be a CLI command for now)
        post("/demobanks") {
            throw NotImplementedError("Only available in the Sandbox CLI.")
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

        /**
         * 'lang' unused.  It works around the /$lang-terminated
         * links that some shops still have in their demo navigation bar.
         */
        get("/demobanks/{demobankid}/{lang?}/") {
            val demobank = ensureDemobank(call)

            /**
             * Respond the SPA if the content type is not "application/json".
             */
            if (call.request.headers["Content-Type"] != "application/json") {
                val spa: InputStream? = ClassLoader.getSystemClassLoader().getResourceAsStream("static/spa.html")
                if (spa == null) throw internalServerError("SPA not found!")
                // load whole SPA from disk.  Now <200KB, fine to block-read it.
                var content = String(spa.readBytes(), Charsets.UTF_8)
                val landingUrl = System.getenv(
                    "TALER_ENV_URL_INTRO") ?: "https://demo.taler.net/"
                content = content.replace("%DEMO_SITE_LANDING_URL%", landingUrl)
                val bankUrl = System.getenv(
                    "TALER_ENV_URL_BANK") ?: "https://demo.taler.net/sandbox/demobanks/default/"
                content = content.replace("%DEMO_SITE_BANK_URL%", bankUrl)
                val blogUrl = System.getenv(
                    "TALER_ENV_URL_MERCHANT_BLOG") ?: "https://demo.taler.net/blog/"
                content = content.replace("%DEMO_SITE_BLOG_URL%", blogUrl)
                val donationsUrl = System.getenv(
                    "TALER_ENV_URL_MERCHANT_DONATIONS") ?: "https://demo.taler.net/donations/"
                content = content.replace("%DEMO_SITE_DONATIONS_URL%", donationsUrl)
                val surveyUrl = System.getenv(
                    "TALER_ENV_URL_MERCHANT_SURVEY") ?: "https://demo.taler.net/survey/"
                content = content.replace("%DEMO_SITE_SURVEY_URL%", surveyUrl)
                logger.debug("after links replacement + $content")
                call.respondText(content, ContentType.Text.Html)
                return@get
            }
            expectAdmin(call.request.basicAuth())
            call.respond(getJsonFromDemobankConfig(demobank))
            return@get
        }
        route("/demobanks/{demobankid}") {

            // NOTE: TWG assumes that username == bank account label.
            route("/taler-wire-gateway") {
                post("/{exchangeUsername}/admin/add-incoming") {
                    val username = call.getUriComponent("exchangeUsername")
                    val usernameAuth = call.request.basicAuth()
                    if (username != usernameAuth) {
                        throw forbidden(
                            "Bank account name and username differ: $username vs $usernameAuth"
                        )
                    }
                    logger.debug("TWG add-incoming passed authentication")
                    val body = call.receiveJson<TWGAdminAddIncoming>()
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
                    call.respond(object {
                        val name = "taler-bank-integration"
                        // FIXME: avoid hard-coding the version!
                        val version = "0:0:0"
                        val currency = demobank.currency
                    })
                    return@get
                }
                post("/withdrawal-operation/{wopid}") {
                    val wopid: String = ensureNonNull(call.parameters["wopid"])
                    val body = call.receiveJson<TalerWithdrawalSelection>()
                    val transferDone = newSuspendedTransaction(context = singleThreadContext) {
                        val wo = TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq java.util.UUID.fromString(wopid)
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound, "Withdrawal operation $wopid not found."
                        )
                        if (wo.confirmationDone) {
                            return@newSuspendedTransaction true
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
                            return@newSuspendedTransaction false
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
                    val wopid: String = ensureNonNull(call.parameters["wopid"])
                    val wo = transaction {
                        TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq java.util.UUID.fromString(wopid)
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound,
                            "Withdrawal operation: $wopid not found"
                        )
                    }
                    val demobank = ensureDemobank(call)
                    val ret = TalerWithdrawalStatus(
                        selection_done = wo.selectionDone,
                        transfer_done = wo.confirmationDone,
                        amount = "${demobank.currency}:${wo.amount}",
                        suggested_exchange = demobank.suggestedExchangeBaseUrl,
                        aborted = wo.aborted
                    )
                    call.respond(ret)
                    return@get
                }
            }
            // Talk to Web UI.
            route("/access-api") {
                post("/accounts/{account_name}/transactions") {
                    val bankAccount = getBankAccountWithAuth(call)
                    val req = call.receiveJson<NewTransactionReq>()
                    val payto = parsePayto(req.paytoUri)
                    val amount: String? = payto.amount ?: req.amount
                    if (amount == null) throw badRequest("Amount is missing")
                    /**
                     * Need a transaction block only to let the
                     * 'demoBank' field of 'bankAccount' accessed.
                     *
                     * This could be fixed by making 'getBankAccountWithAuth()'
                     * return a pair, consisting of the bank account and the demobank
                     * hosting it.
                     */
                    transaction {
                        wireTransfer(
                            debitAccount = bankAccount,
                            creditAccount = getBankAccountFromIban(payto.iban),
                            demobank = bankAccount.demoBank,
                            subject = payto.message ?: throw badRequest(
                                "'message' query parameter missing in Payto address"
                            ),
                            amount = parseAmount(amount).amount.toPlainString()
                        )
                    }
                    call.respond(object {})
                    return@post
                }
                // Information about one withdrawal.
                get("/accounts/{account_name}/withdrawals/{withdrawal_id}") {
                    val op = getWithdrawalOperation(call.getUriComponent("withdrawal_id"))
                    val demobank = ensureDemobank(call)
                    if (!op.selectionDone && op.reservePub != null) throw internalServerError(
                        "Unselected withdrawal has a reserve public key",
                        LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE
                    )
                    call.respond(object {
                        val amount = "${demobank.currency}:${op.amount}"
                        val aborted = op.aborted
                        val confirmation_done = op.confirmationDone
                        val selection_done = op.selectionDone
                        val selected_reserve_pub = op.reservePub
                        val selected_exchange_account = op.selectedExchangePayto
                    })
                    return@get
                }
                // Create a new withdrawal operation.
                post("/accounts/{account_name}/withdrawals") {
                    var username = call.request.basicAuth()
                    if (username == null && (!WITH_AUTH)) {
                        logger.info("Authentication is disabled to facilitate tests, defaulting to 'admin' username")
                        username = "admin"
                    }
                    val demobank = ensureDemobank(call)
                    /**
                     * Check here if the user has the right over the claimed bank account.  After
                     * this check, the withdrawal operation will be allowed only by providing its
                     * UID. */
                    val maybeOwnedAccount = getBankAccountFromLabel(
                        call.getUriComponent("account_name"),
                        demobank
                    )
                    if (maybeOwnedAccount.owner != username) throw unauthorized(
                        "Customer '$username' has no rights over bank account '${maybeOwnedAccount.label}'"
                    )
                    val req = call.receiveJson<WithdrawalRequest>()
                    // Check for currency consistency
                    val amount = parseAmount(req.amount)
                    if (amount.currency != demobank.currency) throw badRequest(
                        "Currency ${amount.currency} differs from Demobank's: ${demobank.currency}"
                    )
                    val wo: TalerWithdrawalEntity = transaction {
                        TalerWithdrawalEntity.new {
                        this.amount = amount.amount.toPlainString()
                        walletBankAccount = maybeOwnedAccount
                        }
                    }
                    val baseUrl = URL(call.request.getBaseUrl())
                    val withdrawUri = url {
                        protocol = URLProtocol(
                            "taler".plus(if (baseUrl.protocol.lowercase() == "http") "+http" else ""),
                            -1
                        )
                        host = "withdraw"
                        pathComponents(
                            /**
                             * encodes the hostname(+port) of the actual
                             * bank that will serve the withdrawal request.
                             */
                            baseUrl.host.plus(
                                if (baseUrl.port != -1)
                                    ":${baseUrl.port}"
                                else ""
                            ),
                            baseUrl.path, // has x-forwarded-prefix, or single slash.
                            "demobanks",
                            demobank.name,
                            "integration-api",
                            wo.wopid.toString()
                        )
                    }
                    call.respond(object {
                        val withdrawal_id = wo.wopid.toString()
                        val taler_withdraw_uri = withdrawUri
                    })
                    return@post
                }
                // Confirm a withdrawal: no basic auth, because the ID should be unguessable.
                post("/accounts/{account_name}/withdrawals/{withdrawal_id}/confirm") {
                    val withdrawalId = call.getUriComponent("withdrawal_id")
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
                            wo.selectedExchangePayto = demobank.suggestedExchangePayto
                        }
                        val exchangeBankAccount = getBankAccountFromPayto(
                            wo.selectedExchangePayto ?: throw internalServerError(
                                "Cannot withdraw without an exchange."
                            )
                        )
                        if (!wo.confirmationDone) {
                            // Need the exchange bank account!
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
                    return@post
                }
                post("/accounts/{account_name}/withdrawals/{withdrawal_id}/abort") {
                    val withdrawalId = call.getUriComponent("withdrawal_id")
                    val operation = getWithdrawalOperation(withdrawalId)
                    if (operation.confirmationDone) throw conflict("Cannot abort paid withdrawal.")
                    transaction { operation.aborted = true }
                    call.respond(object {})
                    return@post
                }
                // Bank account basic information.
                get("/accounts/{account_name}") {
                    val username = call.request.basicAuth()
                    val accountAccessed = call.getUriComponent("account_name")
                    val demobank = ensureDemobank(call)
                    val bankAccount = transaction {
                        val res = BankAccountEntity.find {
                            (BankAccountsTable.label eq accountAccessed).and(
                                BankAccountsTable.demoBank eq demobank.id
                            )
                        }.firstOrNull()
                        res
                    } ?: throw notFound("Account '$accountAccessed' not found")
                    // Check rights.
                    if (
                        WITH_AUTH
                        && (bankAccount.owner != username && username != "admin")
                    ) throw forbidden(
                            "Customer '$username' cannot access bank account '$accountAccessed'"
                        )
                    val balance = balanceForAccount(bankAccount)
                    call.respond(object {
                        val balance = object {
                            val amount = "${demobank.currency}:${balance.abs(). toPlainString()}"
                            val credit_debit_indicator = if (balance < BigDecimal.ZERO) "debit" else "credit"
                        }
                        val paytoUri = buildIbanPaytoUri(
                            iban = bankAccount.iban,
                            bic = bankAccount.bic,
                            receiverName = getPersonNameFromCustomer(username ?: "admin")
                        )
                    })
                    return@get
                }
                get("/accounts/{account_name}/transactions/{tId}") {
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(
                        call.getUriComponent("account_name"),
                        demobank
                    )
                    val authOk: Boolean = bankAccount.isPublic || (!WITH_AUTH)
                    if (!authOk && (call.request.basicAuth() != bankAccount.owner)) throw forbidden(
                        "Cannot access bank account ${bankAccount.label}"
                    )
                    // Flow here == Right on the bank account.
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
                    val demobank = ensureDemobank(call)
                    val bankAccount = getBankAccountFromLabel(
                        call.getUriComponent("account_name"),
                        demobank
                    )
                    val authOk: Boolean = bankAccount.isPublic || (!WITH_AUTH)
                    if (!authOk && (call.request.basicAuth() != bankAccount.owner)) throw forbidden(
                        "Cannot access bank account ${bankAccount.label}"
                    )

                    val page: Int = Integer.decode(call.request.queryParameters["page"] ?: "1")
                    val size: Int = Integer.decode(call.request.queryParameters["size"] ?: "5")

                    val ret = mutableListOf<RawPayment>()
                    /**
                     * Case where page number wasn't given,
                     * therefore the results starts from the last transaction. */
                    transaction {
                        /**
                         * Get a history page - from the calling bank account - having
                         * 'firstElementId' as the latest transaction in it.  */
                        fun getPage(firstElementId: Long): Iterable<BankAccountTransactionEntity> {
                            logger.debug("History page from tx $firstElementId, including $size txs in the past.")
                            return BankAccountTransactionEntity.find {
                                (BankAccountTransactionsTable.id lessEq firstElementId) and
                                        (BankAccountTransactionsTable.account eq bankAccount.id)
                            }.sortedByDescending { it.id.value }.take(size)
                        }
                        val lt: BankAccountTransactionEntity? = bankAccount.lastTransaction
                        if (lt == null) return@transaction
                        var nextPageIdUpperLimit: Long = lt.id.value
                        /**
                         * This loop fetches (and discards) pages until the
                         * desired one is found.  */
                        for (i in 0..(page)) {
                            val pageBuf = getPage(nextPageIdUpperLimit)
                            logger.debug("Processing page:")
                            pageBuf.forEach { logger.debug("${it.id} ${it.subject} ${it.amount}") }
                            if (pageBuf.none()) return@transaction
                            nextPageIdUpperLimit = pageBuf.last().id.value - 1
                            if (i == page) pageBuf.forEach {
                                ret.add(getHistoryElementFromTransactionRow(it))
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
                            val balanceIter = balanceForAccount(it)
                            ret.publicAccounts.add(
                                PublicAccountInfo(
                                    balance = "${demobank.currency}:$balanceIter",
                                    iban = it.iban,
                                    accountLabel = it.label
                                )
                            )
                        }
                    }
                    call.respond(ret)
                    return@get
                }
                // Keeping the prefix "testing" not to break tests.
                post("/testing/register") {
                    // Check demobank was created.
                    val demobank = ensureDemobank(call)
                    val req = call.receiveJson<CustomerRegistration>()
                    val checkExist = transaction {
                        DemobankCustomerEntity.find {
                            DemobankCustomersTable.username eq req.username
                        }.firstOrNull()
                    }
                    if (checkExist != null) {
                        throw SandboxError(
                            HttpStatusCode.Conflict,
                            "Username ${req.username} not available."
                        )
                    }
                    // Create new customer.
                    requireValidResourceName(req.username)
                    val bankAccount = transaction {
                        val bankAccount = BankAccountEntity.new {
                            iban = req.iban ?: getIban()
                            /**
                             * For now, keep same semantics of Pybank: a username
                             * is AS WELL a bank account label.  In other words, it
                             * identifies a customer AND a bank account.
                             */
                            label = req.username
                            owner = req.username
                            this.demoBank = demobank
                            isPublic = req.isPublic
                        }
                        DemobankCustomerEntity.new {
                            username = req.username
                            passwordHash = CryptoUtil.hashpw(req.password)
                        }
                        bankAccount.bonus("${demobank.currency}:100")
                        bankAccount
                    }
                    val balance = balanceForAccount(bankAccount)
                    call.respond(object {
                        val balance = object {
                            val amount = "${demobank.currency}:$balance"
                            val credit_debit_indicator = "CRDT"
                        }
                        val paytoUri = buildIbanPaytoUri(
                            iban = bankAccount.iban,
                            bic = bankAccount.bic,
                            receiverName = getPersonNameFromCustomer(req.username)
                        )
                    })
                    return@post
                }
            }
            route("/ebics") {
                post("/subscribers") {
                    // Only the admin can create Ebics subscribers.
                    val user = call.request.basicAuth()
                    if (user != "admin") throw forbidden("Only the Admin can create Ebics subscribers.")
                    val body = call.receiveJson<EbicsSubscriberInfo>()
                    // Create or get the Ebics subscriber that is found.
                    transaction {
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

fun serverMain(port: Int) {
    val server = embeddedServer(Netty, port = port, module = sandboxApp)
    logger.info("LibEuFin Sandbox running on port $port")
    try {
        server.start(wait = true)
    } catch (e: BindException) {
        logger.error(e.message)
        exitProcess(1)
    }
}
