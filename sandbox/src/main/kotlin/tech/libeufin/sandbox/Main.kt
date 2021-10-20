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
import io.ktor.auth.*
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
import io.ktor.util.pipeline.*
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
import validatePlainAmount
import java.math.BigDecimal
import java.net.BindException
import java.net.URL
import java.security.interfaces.RSAPublicKey
import javax.xml.bind.JAXBContext
import kotlin.system.exitProcess

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")
private val currencyEnv: String? = System.getenv("LIBEUFIN_SANDBOX_CURRENCY")
private val envName: String? = System.getenv("TALER_ENV_NAME")
const val SANDBOX_DB_ENV_VAR_NAME = "LIBEUFIN_SANDBOX_DB_CONNECTION"
private val adminPassword: String? = System.getenv("LIBEUFIN_SANDBOX_ADMIN_PASSWORD")
private var WITH_AUTH = true

data class SandboxError(
    val statusCode: HttpStatusCode,
    val reason: String,
    val errorCode: LibeufinErrorCode? = null
) : Exception()

data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

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
                DemobankConfigEntity.new {
                    currency = currencyOption
                    bankDebtLimit = bankDebtLimitOption
                    usersDebtLimit = usersDebtLimitOption
                    allowRegistrations = allowRegistrationsOption
                    name = nameOption
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
            "will be inserted in a new Camt.053 report"
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
    private val amount by argument(help = "Amount, in the \$currency:x.y format")
    private val subjectArg by argument(name = "subject", help = "Payment's subject")

    override fun run() {
        val dbConnString = getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME)
        Database.connect(dbConnString)
        try {
            wireTransfer(debitAccount, creditAccount, amount, subjectArg)
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
                "--port, when both are given", metavar = "PATH"
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

private fun getCustomerFromDb(username: String): DemobankCustomerEntity {
    return transaction { DemobankCustomerEntity.find {
        DemobankCustomersTable.username eq username
    }.firstOrNull() } ?: throw notFound("Customer with username '$username' not found")
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
    val currency: String?
)

data class BankAccountsListReponse(
    val accounts: List<BankAccountInfo>
)

inline fun <reified T> Document.toObject(): T {
    val jc = JAXBContext.newInstance(T::class.java)
    val m = jc.createUnmarshaller()
    return m.unmarshal(this, T::class.java).value
}

fun BigDecimal.signToString(): String {
    return if (this.signum() > 0) "+" else ""
    // minus sign is added by default already.
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
        Camt053Tick()
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
    install(io.ktor.features.CallLogging) {
        this.level = org.slf4j.event.Level.DEBUG
        this.logger = logger
    }
    install(Authentication) {
        // Web-based authentication for Bank customers.
        form("auth-form") {
            userParamName = "username"
            passwordParamName = "password"
            validate { credentials ->
                if (credentials.name == "test") {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
    install(io.ktor.features.ContentNegotiation) {
        jackson {
            enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                indentArraysWith(com.fasterxml.jackson.core.util.DefaultPrettyPrinter.FixedSpaceIndenter.instance)
                indentObjectsWith(DefaultIndenter("  ", "\n"))
            })
            registerModule(KotlinModule(nullisSameAsDefault = true))
            //registerModule(JavaTimeModule())
        }
    }
    install(StatusPages) {
        exception<ArithmeticException> { cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause)
            call.respondText(
                "Invalid arithmetic attempted.",
                io.ktor.http.ContentType.Text.Plain,
                // here is always the bank's fault, as it should always check
                // the operands.
                io.ktor.http.HttpStatusCode.InternalServerError
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
            val resp = tech.libeufin.util.ebics_h004.EbicsResponse.createForUploadWithError(
                e.errorText,
                e.errorCode,
                // assuming that the phase is always transfer,
                // as errors during initialization should have
                // already been caught by the chunking logic.
                tech.libeufin.util.ebics_h004.EbicsTypes.TransactionPhaseType.TRANSFER
            )
            val hostAuthPriv = transaction {
                val host = EbicsHostEntity.find {
                    tech.libeufin.sandbox.EbicsHostsTable.hostID.upperCase() eq call.attributes.get(tech.libeufin.sandbox.EbicsHostIdAttribute)
                        .uppercase()
                }.firstOrNull() ?: throw SandboxError(
                    io.ktor.http.HttpStatusCode.InternalServerError,
                    "Requested Ebics host ID not found."
                )
                tech.libeufin.util.CryptoUtil.loadRsaPrivateKey(host.authenticationPrivateKey.bytes)
            }
            call.respondText(
                tech.libeufin.util.XMLUtil.signEbicsResponse(resp, hostAuthPriv),
                io.ktor.http.ContentType.Application.Xml,
                io.ktor.http.HttpStatusCode.OK
            )
        }
        exception<Throwable> { cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause)
            call.respondText(
                "Internal server error.",
                io.ktor.http.ContentType.Text.Plain,
                io.ktor.http.HttpStatusCode.InternalServerError
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
            call.respondText("Hello, this is Sandbox\n", ContentType.Text.Plain)
        }

        // Respond with the last statement of the requesting account.
        // Query details in the body.
        post("/admin/payments/camt") {
            call.request.basicAuth()
            val body = call.receiveJson<CamtParams>()
            val bankaccount = getBankAccountFromLabel(body.bankaccount)
            if (body.type != 53) throw SandboxError(
                HttpStatusCode.NotFound,
                "Only Camt.053 documents can be generated."
            )
            val camtMessage = transaction {
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
                    currency = body.currency ?: "EUR"
                    owner = username ?: "admin" // allows
                }
            }
            call.respond(object {})
            return@post
        }

        // Information about one bank account.
        get("/admin/bank-accounts/{label}") {
            call.request.basicAuth()
            val label = ensureNonNull(call.parameters["label"])
            val ret = transaction {
                val bankAccount = tech.libeufin.sandbox.BankAccountEntity.find {
                    tech.libeufin.sandbox.BankAccountsTable.label eq label
                }.firstOrNull() ?: throw SandboxError(
                    io.ktor.http.HttpStatusCode.NotFound,
                    "Account '$label' not found"
                )
                val balance = balanceForAccount(bankAccount)
                object {
                    val balance = "${bankAccount.currency}:${balance}"
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
                    io.ktor.http.HttpStatusCode.BadRequest,
                    "invalid amount (should be plain amount without currency)"
                )
            }
            val reqDebtorBic = body.debtorBic
            if (reqDebtorBic != null && !validateBic(reqDebtorBic)) {
                throw SandboxError(
                    io.ktor.http.HttpStatusCode.BadRequest,
                    "invalid BIC"
                )
            }
            transaction {
                val account = getBankAccountFromLabel(accountLabel)
                val randId = getRandomString(16)
                tech.libeufin.sandbox.BankAccountTransactionEntity.new {
                    creditorIban = account.iban
                    creditorBic = account.bic
                    creditorName = "Creditor Name" // FIXME: Waits to get this value from the DemobankCustomer type.
                    debtorIban = body.debtorIban
                    debtorBic = reqDebtorBic
                    debtorName = body.debtorName
                    subject = body.subject
                    amount = body.amount
                    currency = account.currency
                    date = getUTCnow().toInstant().toEpochMilli()
                    accountServicerReference = "sandbox-$randId"
                    this.account = account
                    direction = "CRDT"
                }
            }
            call.respond(object {})
        }


        // Associates a new bank account with an existing Ebics subscriber.
        post("/admin/ebics/bank-accounts") {
            val username = call.request.basicAuth()
            val body = call.receiveJson<BankAccountRequest>()
            if (!validateBic(body.bic)) {
                throw SandboxError(HttpStatusCode.BadRequest, "invalid BIC (${body.bic})")
            }
            transaction {
                val subscriber = getEbicsSubscriberFromDetails(
                    body.subscriber.userID,
                    body.subscriber.partnerID,
                    body.subscriber.hostID
                )
                val check = BankAccountEntity.find {
                    BankAccountsTable.iban eq body.iban or (BankAccountsTable.label eq body.label)
                }.count()
                if (check > 0) throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "Either IBAN or account label were already taken; please choose fresh ones"
                )
                subscriber.bankAccount = BankAccountEntity.new {
                    iban = body.iban
                    bic = body.bic
                    label = body.label
                    currency = body.currency.uppercase(java.util.Locale.ROOT)
                    owner = username ?: "admin"
                }
            }
            call.respondText("Bank account created")
            return@post
        }

        // Information about all the bank accounts.
        get("/admin/bank-accounts") {
            call.request.basicAuth()
            val accounts = mutableListOf<BankAccountInfo>()
            transaction {
                BankAccountEntity.all().forEach {
                    accounts.add(
                        BankAccountInfo(
                            label = it.label,
                            bic = it.bic,
                            iban = it.iban,
                            currency = it.currency,
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
                    val account = getBankAccountFromLabel(accountLabel)
                    tech.libeufin.sandbox.BankAccountTransactionEntity.find {
                        tech.libeufin.sandbox.BankAccountTransactionsTable.account eq account.id
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
                val account = getBankAccountFromLabel(accountLabel)
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
                        currency = account.currency
                        date = getUTCnow().toInstant().toEpochMilli()
                        accountServicerReference = transactionReferenceCrdt
                        this.account = account
                        direction = "CRDT"
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
                        currency = account.currency
                        date = getUTCnow().toInstant().toEpochMilli()
                        accountServicerReference = transactionReferenceDbit
                        this.account = account
                        direction = "DBIT"
                    }
                }
            }
            call.respond(object {})
        }

        // Creates a new Ebics subscriber.
        post("/admin/ebics/subscribers") {
            call.request.basicAuth()
            val body = call.receiveJson<EbicsSubscriberElement>()
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
                tech.libeufin.sandbox.EbicsSubscriberEntity.all().forEach {
                    ret.subscribers.add(
                        EbicsSubscriberElement(
                            userID = it.userId,
                            partnerID = it.partnerId,
                            hostID = it.hostId
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
                val pairA = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
                val pairB = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
                val pairC = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
                host.authenticationPrivateKey = ExposedBlob(pairA.private.encoded)
                host.encryptionPrivateKey = ExposedBlob(pairB.private.encoded)
                host.signaturePrivateKey = ExposedBlob(pairC.private.encoded)
            }
            call.respondText(
                "Keys of '${hostID}' rotated.",
                io.ktor.http.ContentType.Text.Plain,
                io.ktor.http.HttpStatusCode.OK
            )
            return@post
        }

        // Create a new EBICS host
        post("/admin/ebics/hosts") {
            call.request.basicAuth()
            val req = call.receiveJson<EbicsHostCreateRequest>()
            val pairA = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
            val pairB = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
            val pairC = tech.libeufin.util.CryptoUtil.generateRsaKeyPair(2048)
            transaction {
                tech.libeufin.sandbox.EbicsHostEntity.new {
                    this.ebicsVersion = req.ebicsVersion
                    this.hostId = req.hostID
                    this.authenticationPrivateKey = ExposedBlob(pairA.private.encoded)
                    this.encryptionPrivateKey = ExposedBlob(pairB.private.encoded)
                    this.signaturePrivateKey = ExposedBlob(pairC.private.encoded)
                }
            }
            call.respondText(
                "Host '${req.hostID}' created.",
                io.ktor.http.ContentType.Text.Plain,
                io.ktor.http.HttpStatusCode.OK
            )
            return@post
        }

        // Show the names of all the Ebics hosts
        get("/admin/ebics/hosts") {
            call.request.basicAuth()
            val ebicsHosts = transaction {
                tech.libeufin.sandbox.EbicsHostEntity.all().map { it.hostId }
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
                throw EbicsProcessingError("Serving EBICS threw unmanaged UtilError: ${e.reason}")
            }
            catch (e: SandboxError) {
                // Should translate to EBICS error code.
                when (e.errorCode) {
                    tech.libeufin.util.LibeufinErrorCode.LIBEUFIN_EC_INVALID_STATE -> throw EbicsProcessingError("Invalid bank state.")
                    tech.libeufin.util.LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE -> throw EbicsProcessingError("Inconsistent bank state.")
                    else -> throw EbicsProcessingError("Unknown LibEuFin error code: ${e.errorCode}.")
                }
            }
            catch (e: EbicsRequestError) {
                // Preventing the last catch-all block
                // from capturing a known type.
                throw e
            }
            catch (e: Exception) {
                if (e !is EbicsRequestError) {
                    throw EbicsProcessingError("Unmanaged error: $e")
                }
            }
            return@post
        }

        get("/api/config") {
            SandboxAssert(
                currencyEnv != null,
                "Currency not found.  Logs should have warned"
            )
            call.respond(object {
                val name = "taler-bank-integration"

                // FIXME: use actual version here!
                val version = "0:0:0"
                val currency = currencyEnv
            })
        }

        // Create a new demobank instance with a particular currency,
        // debt limit and possibly other configuration
        // (could also be a CLI command for now)
        post("/demobanks") {
            throw NotImplementedError("Only available in the CLI.")
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
            expectAdmin(call.request.basicAuth())
            val demobankId = call.getUriComponent("demobankid")
            val ret: DemobankConfigEntity = transaction {
                DemobankConfigEntity.find {
                    DemobankConfigsTable.name eq demobankId
                }.firstOrNull()
            } ?: throw notFound("Demobank ${demobankId} not found")
            call.respond(getJsonFromDemobankConfig(ret))
            return@get
        }

        route("/demobanks/{demobankid}") {

            // Talk to wallets.
            route("/integration-api") {
                post("/api/withdrawal-operation/{wopid}") {
                    val wopid: String = ensureNonNull(call.parameters["wopid"])
                    val body = call.receiveJson<TalerWithdrawalSelection>()
                    val transferDone = newSuspendedTransaction(context = singleThreadContext) {
                        val wo = TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq java.util.UUID.fromString(wopid)
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound, "Withdrawal operation $wopid not found."
                        )
                        if (wo.selectionDone) {
                            if (wo.transferDone) {
                                logger.info("Wallet performs again this operation that was paid out earlier: idempotent")
                                return@newSuspendedTransaction
                            }
                            // Selected already but NOT paid, check consistency.
                            if (body.reserve_pub != wo.reservePub) throw SandboxError(
                                HttpStatusCode.Conflict,
                                "Selecting a different reserve from the one already selected"
                            )
                            if (body.selected_exchange != wo.selectedExchangePayto) throw SandboxError(
                                HttpStatusCode.Conflict,
                                "Selecting a different exchange from the one already selected"
                            )
                        }
                        wo.reservePub = body.reserve_pub
                        wo.selectedExchangePayto = body.selected_exchange
                        wo.selectionDone = true
                        wo.transferDone
                    }
                    call.respond(object {
                        val transfer_done = transferDone
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
                        transfer_done = wo.transferDone,
                        amount = wo.amount,
                        suggested_exchange = demobank.suggestedExchange
                    )
                    call.respond(ret)
                    return@get
                }
            }
            // Talk to Web UI.
            route("/access-api") {
                // Create a new withdrawal operation.
                post("/accounts/{account_name}/withdrawals") {
                    val username = call.request.basicAuth()
                    if (username == null) throw badRequest(
                        "Taler withdrawal tried with authentication disabled. " +
                                "That is impossible, because no bank account can get this operation debited."
                    )
                    val demobank = ensureDemobank(call)
                    /**
                     * Check here if the user has the right over the claimed bank account.  After
                     * this check, the withdrawal operation will be allowed only by providing its
                     * UID.
                     */
                    val maybeOwnedAccount = getBankAccountFromLabel(call.getUriComponent("account_name"))
                    if (maybeOwnedAccount.owner != username) throw unauthorized(
                        "Customer '$username' has no rights over bank account '${maybeOwnedAccount.label}'"
                    )
                    val wo: TalerWithdrawalEntity = transaction { TalerWithdrawalEntity.new {
                        amount = "${demobank.currency}:5"
                        walletBankAccount = maybeOwnedAccount
                    } }
                    val baseUrl = URL(call.request.getBaseUrl())
                    val withdrawUri = call.url {
                        protocol = URLProtocol(
                            "taler".plus(if (baseUrl.protocol.lowercase() == "http") "+http" else ""),
                            -1
                        )
                        pathComponents(baseUrl.path, "access-api", wo.wopid.toString())
                        encodedPath += "/"
                    }
                    call.respond(object {
                        val withdrawal_id = wo.id.value
                        val taler_withdraw_uri = withdrawUri
                    })
                    return@post
                }
                // Confirm a withdrawal.
                post("/accounts/{account_name}/withdrawals/{withdrawal_id}/confirm") {
                    val withdrawalId = call.getUriComponent("withdrawal_id")
                    transaction {
                        val wo = TalerWithdrawalEntity.find {
                            TalerWithdrawalsTable.wopid eq java.util.UUID.fromString(withdrawalId)
                        }.firstOrNull() ?: throw SandboxError(
                            HttpStatusCode.NotFound, "Withdrawal operation $withdrawalId not found."
                        )
                        if (wo.aborted) throw SandboxError(
                            HttpStatusCode.Conflict,
                            "Cannot confirm an aborted withdrawal."
                        )
                        if (!wo.selectionDone) throw SandboxError(
                            HttpStatusCode.UnprocessableEntity,
                            "Cannot confirm a unselected withdrawal: " +
                                    "specify exchange and reserve public key via Integration API first."
                        )
                        val exchangeBankAccount = getBankAccountFromPayto(
                            wo.selectedExchangePayto ?: throw internalServerError(
                                "Cannot withdraw without an exchange."
                            )
                        )
                        if (!wo.transferDone) {
                            // Need the exchange bank account!
                            wireTransfer(
                                debitAccount = wo.walletBankAccount,
                                creditAccount = exchangeBankAccount,
                                amount = wo.amount,
                                subject = wo.reservePub ?: throw internalServerError(
                                    "Cannot transfer funds without reserve public key."
                                ),
                                demoBank = ensureDemobank(call)
                            )
                            wo.transferDone = true
                        }
                        wo.transferDone
                    }
                    call.respond(object {})
                    return@post
                }
                // Bank account basic information.
                get("/accounts/{account_name}") {
                    val username = call.request.basicAuth()
                    val accountAccessed = call.getUriComponent("account_name")
                    val bankAccount = transaction {
                        val res = BankAccountEntity.find {
                            BankAccountsTable.label eq accountAccessed
                        }.firstOrNull()
                        res
                    } ?: throw notFound("Account '$accountAccessed' not found")
                    // Check rights.
                    if (WITH_AUTH) {
                        if (bankAccount.owner != username) throw forbidden(
                            "Customer '$username' cannot access bank account '$accountAccessed'"
                        )
                    }
                    val creditDebitIndicator = if (bankAccount.isDebit) {
                        "debit"
                    } else {
                        "credit"
                    }
                    val balance = balanceForAccount(bankAccount)
                    val demobank = ensureDemobank(call)
                    call.respond(object {
                        val balance = {
                            val amount = "${demobank.currency}:${balance}"
                            val credit_debit_indicator = creditDebitIndicator
                        }
                    })
                    return@get
                }
                get("/accounts/{account_name}/history") {
                    // New endpoint, access account history to display in the SPA
                    // (could be merged with GET /accounts/{account_name}
                }
                get("/accounts/public") {
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
                                    iban = it.iban
                                )
                            )
                        }
                    }
                    call.respond(ret)
                    return@get
                }

                get("/accounts/public/{account_name}/history") {
                    // Get transaction history of a public account
                }
                // Keeping the prefix "testing" not to break tests.
                post("/testing/register") {
                    // Check demobank was created.
                    val demobank = ensureDemobank(call)
                    val req = call.receive<CustomerRegistration>()
                    val checkExist = transaction {
                        DemobankCustomerEntity.find {
                            DemobankCustomersTable.username eq req.username
                        }
                    }.firstOrNull()
                    if (checkExist != null) {
                        throw SandboxError(
                            HttpStatusCode.Conflict,
                            "Username ${req.username} not available."
                        )
                    }
                    // Create new customer.
                    requireValidResourceName(req.username)
                    transaction {
                        BankAccountEntity.new {
                            iban = getIban()
                            label = req.username + "acct" // multiple accounts per username not allowed.
                            currency = demobank.currency
                            owner = req.username
                            this.demoBank = demobank.id
                        }
                        DemobankCustomerEntity.new {
                            username = req.username
                            passwordHash = CryptoUtil.hashpw(req.password)
                        }
                    }
                    call.respondText("Registration successful")
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
