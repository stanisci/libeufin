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
import com.fasterxml.jackson.core.JsonParseException
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import io.ktor.jackson.jackson
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.w3c.dom.Document
import tech.libeufin.util.CryptoUtil
import tech.libeufin.util.RawPayment
import java.lang.ArithmeticException
import java.math.BigDecimal
import java.security.interfaces.RSAPublicKey
import javax.xml.bind.JAXBContext
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import execThrowableOrTerminate
import io.ktor.application.ApplicationCall
import io.ktor.auth.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.util.date.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes
import validatePlainAmount
import java.net.BindException
import java.util.*
import kotlin.random.Random
import kotlin.system.exitProcess

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")
private val hostName: String? = getValueFromEnv("LIBEUFIN_SANDBOX_HOSTNAME")
private val currencyEnv: String? = getValueFromEnv("LIBEUFIN_SANDBOX_CURRENCY")
const val SANDBOX_DB_ENV_VAR_NAME = "LIBEUFIN_SANDBOX_DB_CONNECTION"

data class SandboxError(
    val statusCode: HttpStatusCode,
    val reason: String,
    val errorCode: LibeufinErrorCode? = null) : Exception()

data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

class Superuser : CliktCommand("Add superuser or change pw") {
    private val username by argument()
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        execThrowableOrTerminate {
            dbCreateTables(getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME))
        }
        transaction {
            val hashedPw = CryptoUtil.hashpw(password)
            val user = SandboxUserEntity.find { SandboxUsersTable.username eq username }.firstOrNull()
            if (user == null) {
                SandboxUserEntity.new {
                    this.username = this@Superuser.username
                    this.passwordHash = hashedPw
                    this.superuser = true
                }
            } else {
                if (!user.superuser) {
                    println("Can only change password for superuser with this command.")
                    throw ProgramResult(1)
                }
                user.passwordHash = hashedPw
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

    private val hostnameOption by argument(
        "HOSTNAME", help = "hostname that serves this configuration"
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
                SandboxConfigEntity.new {
                    currency = currencyOption
                    bankDebtLimit = bankDebtLimitOption
                    usersDebtLimit = usersDebtLimitOption
                    allowRegistrations = allowRegistrationsOption
                    hostname = hostnameOption
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
            BankAccountEntity.all().forEach { accountIter->
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

    private val logLevel by option()
    private val port by option().int().default(5000)
    override fun run() {
        setLogLevel(logLevel)
        serverMain(getDbConnFromEnv(SANDBOX_DB_ENV_VAR_NAME), port)
    }
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
        Superuser(),
        Serve(),
        ResetTables(),
        Config(),
        MakeTransaction(),
        Camt053Tick()).main(args)
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

fun serverMain(dbName: String, port: Int) {
    execThrowableOrTerminate { dbCreateTables(dbName) }
    val myLogger = logger
    val server = embeddedServer(Netty, port = port) {
        install(CallLogging) {
            this.level = Level.DEBUG
            this.logger = myLogger
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
        install(ContentNegotiation) {
            jackson {
                enable(SerializationFeature.INDENT_OUTPUT)
                setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                    indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
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
                    ContentType.Text.Plain,
                    // here is always the bank's fault, as it should always check
                    // the operands.
                    HttpStatusCode.InternalServerError
                )
            }
            exception<EbicsRequestError> { cause ->
                val resp = EbicsResponse.createForUploadWithError(
                    cause.errorText,
                    cause.errorCode,
                    // assuming that the phase is always transfer,
                    // as errors during initialization should have
                    // already been caught by the chunking logic.
                    EbicsTypes.TransactionPhaseType.TRANSFER
                )

                val hostAuthPriv = transaction {
                    val host = EbicsHostEntity.find {
                        EbicsHostsTable.hostID.upperCase() eq call.attributes.get(EbicsHostIdAttribute).uppercase()
                    }.firstOrNull() ?: throw SandboxError(
                        HttpStatusCode.InternalServerError,
                        "Requested Ebics host ID not found."
                    )
                    CryptoUtil.loadRsaPrivateKey(host.authenticationPrivateKey.bytes)
                }
                call.respondText(
                    XMLUtil.signEbicsResponse(resp, hostAuthPriv),
                    ContentType.Application.Xml,
                    HttpStatusCode.OK
                )
            }
            exception<SandboxError> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
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
            exception<Throwable> { cause ->
                logger.error("Exception while handling '${call.request.uri}'", cause)
                call.respondText("Internal server error.", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
            }
        }
        intercept(ApplicationCallPipeline.Fallback) {
            if (this.call.response.status() == null) {
                call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
                return@intercept finish()
            }
        }
        routing {
            /*

              FIXME: commenting out until a solution for i18n is found.

            get("/bank") {
                val ret = renderTemplate(
                    "login.html",
                    mapOf("csrf_token" to "todo", )
                )
                call.respondText(ret)
                return@get
            } */

            /*
              FIXME: not implemented.

            post("/register") {
                // how to read form-POSTed values?
                val username = "fixme"
                val password = "fixme"
                val superuser = false

                transaction {
                    // check if username is taken.
                    val maybeUser = SandboxUserEntity.find {
                        SandboxUsersTable.username eq username
                    }.firstOrNull()
                    // Will be converted to a HTML response.
                    if (maybeUser != null) throw SandboxError(
                        HttpStatusCode.Conflict, "Username not available"
                    )

                    // username is valid.  Register the user + new bank account.
                    SandboxUserEntity.new {
                        this.username = username
                        passwordHash = CryptoUtil.hashpw(password)
                        this.superuser = superuser
                        bankAccount = BankAccountEntity.new {
                            iban = "fixme"
                            bic = "fixme"
                            name = "fixme"
                            label = "fixme"
                            currency = "fixme"
                        }
                    }
                }

                call.respondText("User $username created")
                return@post
            }

             */

            /*
            FIXME: will likely be replaced by the Single Page Application

            get("/jinja-test") {
                val template = Resources.toString(
                    Resources.getResource("templates/hello.html"),
                    Charsets.UTF_8
                )
                val context = mapOf("token" to "dynamic")
                val page = Jinjava().render(template, context)
                call.respond(page)
                return@get
            }

             */

            /*

            FIXME: not used

            authenticate("auth-form") {
                get("/profile") {
                    val userSession = call.principal<UserIdPrincipal>()
                    println("Welcoming ${userSession?.name}")
                    call.respond(object {})
                    return@get
                }
            }

             */

            /*
            FIXME: not used

            static("/static") {
                /**
                 * Here Sandbox will serve the CSS files.
                 */
                resources("static")
            }
             */

            get("/") {
                call.respondText("Hello, this is Sandbox\n", ContentType.Text.Plain)
            }
            get("/config") {
                call.respond(object {
                    val name = "libeufin-sandbox"

                    // FIXME: use actual version here!
                    val version = "0.0.0-dev.0"
                })
            }
            /**
             * For now, only returns the last statement of the
             * requesting account.
             */
            post("/admin/payments/camt") {
                requireSuperuser(call.request)
                val body = call.receiveJson<CamtParams>()
                val bankaccount = getAccountFromLabel(body.bankaccount)
                if(body.type != 53) throw SandboxError(
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

            post("/admin/bank-accounts/{label}") {
                requireSuperuser(call.request)
                val body = call.receiveJson<BankAccountInfo>()
                transaction {
                    BankAccountEntity.new {
                        iban = body.iban
                        bic = body.bic
                        name = body.name
                        label = body.label
                        currency = body.currency ?: "EUR"
                    }
                }
                call.respond(object {})
                return@post
            }

            get("/admin/bank-accounts/{label}") {
                requireSuperuser(call.request)
                val label = ensureNonNull(call.parameters["label"])
                val ret = transaction {
                    val bankAccount = BankAccountEntity.find {
                        BankAccountsTable.label eq label
                    }.firstOrNull() ?: throw SandboxError(
                        HttpStatusCode.NotFound,
                        "Account '$label' not found"
                    )
                    val balance = balanceForAccount(bankAccount)
                    object {
                        val balance = "${bankAccount.currency}:${balance}"
                        val iban = bankAccount.iban
                        val bic = bankAccount.bic
                        val name = bankAccount.name
                        val label = bankAccount.label
                    }
                }
                call.respond(ret)
                return@get
            }

            post("/admin/bank-accounts/{label}/simulate-incoming-transaction") {
                requireSuperuser(call.request)
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
                    val account = getBankAccountFromLabel(accountLabel)
                    val randId = getRandomString(16)
                    BankAccountTransactionEntity.new {
                        creditorIban = account.iban
                        creditorBic = account.bic
                        creditorName = account.name
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
            
            /**
             * Associates a new bank account with an existing Ebics subscriber.
             */
            post("/admin/ebics/bank-accounts") {
                requireSuperuser(call.request)
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
                        BankAccountsTable.iban eq body.iban or(BankAccountsTable.label eq body.label)
                    }.count()
                    if (check > 0) throw SandboxError(
                        HttpStatusCode.BadRequest,
                        "Either IBAN or account label were already taken; please choose fresh ones"
                    )
                    subscriber.bankAccount = BankAccountEntity.new {
                        iban = body.iban
                        bic = body.bic
                        name = body.name
                        label = body.label
                        currency = body.currency.uppercase(Locale.ROOT)
                    }
                }
                call.respondText("Bank account created")
                return@post
            }
            get("/admin/bank-accounts") {
                requireSuperuser(call.request)
                val accounts = mutableListOf<BankAccountInfo>()
                transaction {
                    BankAccountEntity.all().forEach {
                        accounts.add(
                            BankAccountInfo(
                                label = it.label,
                                name = it.name,
                                bic = it.bic,
                                iban = it.iban,
                                currency = it.currency
                            )
                        )
                    }
                }
                call.respond(accounts)
            }
            get("/admin/bank-accounts/{label}/transactions") {
                requireSuperuser(call.request)
                val ret = AccountTransactions()
                transaction {
                    val accountLabel = ensureNonNull(call.parameters["label"])
                    transaction {
                        val account = getBankAccountFromLabel(accountLabel)
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
            post("/admin/bank-accounts/{label}/generate-transactions") {
                requireSuperuser(call.request)
                transaction {
                    val accountLabel = ensureNonNull(call.parameters["label"])
                    val account = getBankAccountFromLabel(accountLabel)
                    val transactionReferenceCrdt = getRandomString(8)
                    val transactionReferenceDbit = getRandomString(8)

                    run {
                        val amount = Random.nextLong(5, 25)
                        BankAccountTransactionEntity.new {
                            creditorIban = account.iban
                            creditorBic = account.bic
                            creditorName = account.name
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
                        val amount = Random.nextLong(5, 25)

                        BankAccountTransactionEntity.new {
                            debtorIban = account.iban
                            debtorBic = account.bic
                            debtorName = account.name
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
            /**
             * Creates a new Ebics subscriber.
             */
            post("/admin/ebics/subscribers") {
                requireSuperuser(call.request)
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
            /**
             * Shows all the Ebics subscribers' details.
             */
            get("/admin/ebics/subscribers") {
                requireSuperuser(call.request)
                val ret = AdminGetSubscribers()
                transaction {
                    EbicsSubscriberEntity.all().forEach {
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
            post("/admin/ebics/hosts/{hostID}/rotate-keys") {
                requireSuperuser(call.request)
                val hostID: String = call.parameters["hostID"] ?: throw SandboxError(
                    HttpStatusCode.BadRequest, "host ID missing in URL"
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

            /**
             * Creates a new EBICS host.
             */
            post("/admin/ebics/hosts") {
                requireSuperuser(call.request)
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

            /**
             * Show the names of all the Ebics hosts
             */
            get("/admin/ebics/hosts") {
                requireSuperuser(call.request)
                val ebicsHosts = transaction {
                    EbicsHostEntity.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }
            /**
             * Serves all the Ebics requests.
             */
            post("/ebicsweb") {
                try {
                    call.ebicsweb()
                }
                /**
                 * Those errors were all detected by the bank's logic.
                 */
                catch (e: SandboxError) {
                    // Should translate to EBICS error code.
                    when(e.errorCode) {
                        LibeufinErrorCode.LIBEUFIN_EC_INVALID_STATE -> throw EbicsProcessingError("Invalid bank state.")
                        LibeufinErrorCode.LIBEUFIN_EC_INCONSISTENT_STATE -> throw EbicsProcessingError("Inconsistent bank state.")
                        else -> throw EbicsProcessingError("Unknown LibEuFin error code: ${e.errorCode}.")
                    }

                }
                /**
                 * An error occurred, but it wasn't explicitly thrown by the bank.
                 */
                catch (e: Exception) {
                    throw EbicsProcessingError("Unmanaged error: $e")
                }

            }
            /**
             * Activates a withdraw operation of 1 currency unit with
             * the default exchange, from a designated/constant customer.
             */
            get("/taler") {
                requireSuperuser(call.request)
                SandboxAssert(
                    hostName != null,
                    "Own hostname not found.  Logs should have warned"
                )
                SandboxAssert(
                    currencyEnv != null,
                    "Currency not found.  Logs should have warned"
                )
                // check that the three canonical accounts exist
                val wo = transaction {
                    val exchange = BankAccountEntity.find {
                        BankAccountsTable.label eq "sandbox-account-exchange"
                    }.firstOrNull()
                    val customer = BankAccountEntity.find {
                        BankAccountsTable.label eq "sandbox-account-customer"
                    }.firstOrNull()
                    val merchant = BankAccountEntity.find {
                        BankAccountsTable.label eq "sandbox-account-merchant"
                    }.firstOrNull()

                    SandboxAssert(exchange != null, "exchange has no bank account")
                    SandboxAssert(customer != null, "customer has no bank account")
                    SandboxAssert(merchant != null, "merchant has no bank account")

                    // At this point, the three actors exist and a new withdraw operation can be created.
                    TalerWithdrawalEntity.new {
                        // wopid is autogenerated, and momentarily the only column
                    }
                }
                /**
                 * Future versions will include the QR code in this response.
                 */
                call.respondText("taler://withdraw/${hostName}/api/${wo.wopid}")
                return@get
            }
            get("/api/config") {
                SandboxAssert(
                    currencyEnv != null,
                    "Currency not found.  Logs should have warned"
                )
                call.respond(object {
                    val name = "taler-bank-integration"
                    // FIXME: use actual version here!
                    val version = "0.0.0-dev.0"
                    val currency = currencyEnv
                })
            }
            /**
             * not regulating the access here, as the wopid was only granted
             * to logged-in users before (at the /taler endpoint) and has enough
             * entropy to prevent guesses.
             */
            get("/api/withdrawal-operation/{wopid}") {
                val wopid: String = ensureNonNull("wopid")
                val wo = transaction {

                    TalerWithdrawalEntity.find {
                        TalerWithdrawalsTable.wopid eq UUID.fromString(wopid)
                    }.firstOrNull() ?: throw SandboxError(
                        HttpStatusCode.NotFound,
                        "Withdrawal operation: $wopid not found"
                    )
                }
                val ret = TalerWithdrawalStatus(
                    selection_done = wo.selectionDone,
                    transfer_done = wo.transferDone,
                    amount = "${currencyEnv}:1"
                )
                call.respond(ret)
                return@get
            }
            /**
             * Here Sandbox collects the reserve public key to be used
             * as the wire transfer subject, and pays the exchange - which
             * is as well collected in this request.
             */
            post("/withdrawal-operation/{wopid}") {
                val wopid = ensureNonNull("wopid")
                val body = call.receiveJson<TalerWithdrawalConfirmation>()

                transaction {
                    var wo = TalerWithdrawalEntity.find {
                        TalerWithdrawalsTable.wopid eq UUID.fromString(wopid)
                    }.firstOrNull() ?: throw SandboxError(
                        HttpStatusCode.NotFound, "Withdrawal operation $wopid not found."
                    )
                    wireTransfer(
                        "sandbox-account-customer",
                        "sandbox-account-exchange",
                        "$currencyEnv:1",
                        body.reserve_pub
                    )
                    wo.selectionDone = true
                    wo.transferDone = true
                }
                call.respond(object {
                    val transfer_done = true
                })
                return@post
            }
        }
    }
    logger.info("LibEuFin Sandbox running on port $port")
    try {
        server.start(wait = true)
    } catch (e: BindException) {
        logger.error(e.message)
        exitProcess(1)
    }
}
