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

import com.hubspot.jinjava.Jinjava
import com.hubspot.jinjava.JinjavaConfig
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
import java.time.Instant
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.int
import com.google.common.collect.Maps
import com.google.common.io.Resources
import execThrowableOrTerminate
import io.ktor.application.ApplicationCall
import io.ktor.auth.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.util.date.*
import tech.libeufin.sandbox.BankAccountTransactionsTable.accountServicerReference
import tech.libeufin.sandbox.BankAccountTransactionsTable.amount
import tech.libeufin.sandbox.BankAccountTransactionsTable.creditorBic
import tech.libeufin.sandbox.BankAccountTransactionsTable.creditorIban
import tech.libeufin.sandbox.BankAccountTransactionsTable.creditorName
import tech.libeufin.sandbox.BankAccountTransactionsTable.currency
import tech.libeufin.sandbox.BankAccountTransactionsTable.date
import tech.libeufin.sandbox.BankAccountTransactionsTable.debtorBic
import tech.libeufin.sandbox.BankAccountTransactionsTable.debtorIban
import tech.libeufin.sandbox.BankAccountTransactionsTable.debtorName
import tech.libeufin.sandbox.BankAccountTransactionsTable.direction
import tech.libeufin.sandbox.BankAccountTransactionsTable.pmtInfId
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes
import java.net.BindException
import java.util.*
import kotlin.random.Random
import kotlin.reflect.jvm.internal.impl.load.kotlin.JvmType
import kotlin.system.exitProcess

val SANDBOX_DB_ENV_VAR_NAME = "LIBEUFIN_SANDBOX_DB_CONNECTION"
private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

data class SandboxError(val statusCode: HttpStatusCode, val reason: String) : Exception()
data class SandboxErrorJson(val error: SandboxErrorDetailJson)
data class SandboxErrorDetailJson(val type: String, val description: String)

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

data class Subscriber(
    val partnerID: String,
    val userID: String,
    val systemID: String?,
    val keys: SubscriberKeys
)

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
    val bic: String
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
    SandboxCommand().subcommands(Serve(), ResetTables()).main(args)
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
                        EbicsHostsTable.hostID.upperCase() eq call.attributes.get(EbicsHostIdAttribute).toUpperCase()
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

            post("/register") {
                // how to read form-POSTed values?
                val username = "fixme"
                val password = "fixme"
                val superuser = false

                transaction {
                    // check if username is taken.
                    var maybeUser = SandboxUserEntity.find {
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

            authenticate("auth-form") {
                get("/profile") {
                    val userSession = call.principal<UserIdPrincipal>()
                    println("Welcoming ${userSession?.name}")
                    call.respond(object {})
                    return@get
                }
            }

            static("/static") {
                /**
                 * Here Sandbox will serve the CSS files.
                 */
                resources("static")
            }

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
            // only reason for a post is to hide the iban (to some degree.)
            post("/admin/payments/camt") {
                val body = call.receiveJson<CamtParams>()
                val history = historyForAccount(body.iban)
                SandboxAssert(body.type == 53, "Only Camt.053 is implemented")
                val camt53 = buildCamtString(body.type, body.iban, history)
                call.respondText(camt53, ContentType.Text.Xml, HttpStatusCode.OK)
                return@post
            }

            /**
             * Adds a new payment to the book.
             */
            post("/admin/payments") {
                val body = call.receiveJson<RawPayment>()
                val randId = getRandomString(16)
                transaction {
                    val localIban = if (body.direction == "DBIT") body.debitorIban else body.creditorIban
                    BankAccountTransactionsTable.insert {
                        it[creditorIban] = body.creditorIban
                        it[creditorBic] = body.creditorBic
                        it[creditorName] = body.creditorName
                        it[debtorIban] = body.debitorIban
                        it[debtorBic] = body.debitorBic
                        it[debtorName] = body.debitorName
                        it[subject] = body.subject
                        it[amount] = body.amount
                        it[currency] = body.currency
                        it[date] = Instant.now().toEpochMilli()
                        it[accountServicerReference] = "sandbox-$randId"
                        it[account] = getBankAccountFromIban(localIban).id
                        it[direction] = body.direction
                    }
                }
                call.respondText("Payment created")
                return@post
            }

            post("/admin/bank-accounts/{label}/simulate-incoming-transaction") {
                val body = call.receiveJson<IncomingPaymentInfo>()
                // FIXME: generate nicer UUID!
                val accountLabel = ensureNonNull(call.parameters["label"])
                transaction {
                    val account = getBankAccountFromLabel(accountLabel)
                    val randId = getRandomString(16)
                    BankAccountTransactionsTable.insert {
                        it[creditorIban] = account.iban
                        it[creditorBic] = account.bic
                        it[creditorName] = account.name
                        it[debtorIban] = body.debtorIban
                        it[debtorBic] = body.debtorBic
                        it[debtorName] = body.debtorName
                        it[subject] = body.subject
                        it[amount] = body.amount
                        it[currency] = account.currency
                        it[date] = Instant.now().toEpochMilli()
                        it[accountServicerReference] = "sandbox-$randId"
                        it[BankAccountTransactionsTable.account] = account.id
                        it[direction] = "CRDT"
                    }
                }
                call.respond(object {})
            }
            /**
             * Associates a new bank account with an existing Ebics subscriber.
             */
            post("/admin/ebics/bank-accounts") {
                val body = call.receiveJson<BankAccountRequest>()
                transaction {
                    var subscriber = getEbicsSubscriberFromDetails(
                        body.subscriber.userID,
                        body.subscriber.partnerID,
                        body.subscriber.hostID
                    )
                    subscriber.bankAccount = BankAccountEntity.new {
                        iban = body.iban
                        bic = body.bic
                        name = body.name
                        label = body.label
                        currency = body.currency.toUpperCase(Locale.ROOT)
                    }
                }
                call.respondText("Bank account created")
                return@post
            }
            get("/admin/bank-accounts") {
                val accounts = mutableListOf<BankAccountInfo>()
                transaction {
                    BankAccountEntity.all().forEach {
                        accounts.add(
                            BankAccountInfo(
                                label = it.label,
                                name = it.name,
                                bic = it.bic,
                                iban = it.iban
                            )
                        )
                    }
                }
                call.respond(accounts)
            }
            get("/admin/bank-accounts/{label}/transactions") {
                val ret = AccountTransactions()
                transaction {
                    val accountLabel = ensureNonNull(call.parameters["label"])
                    transaction {
                        val account = getBankAccountFromLabel(accountLabel)
                        BankAccountTransactionsTable.select { BankAccountTransactionsTable.account eq account.id }
                            .forEach {
                                ret.payments.add(
                                    PaymentInfo(
                                        accountLabel = account.label,
                                        creditorIban = it[creditorIban],
                                        // FIXME: We need to modify the transactions table to have an actual
                                        // account servicer reference here.
                                        accountServicerReference = it[accountServicerReference],
                                        paymentInformationId = it[pmtInfId],
                                        debtorIban = it[debtorIban],
                                        subject = it[BankAccountTransactionsTable.subject],
                                        date = GMTDate(it[date]).toHttpDate(),
                                        amount = it[amount],
                                        creditorBic = it[creditorBic],
                                        creditorName = it[creditorName],
                                        debtorBic = it[debtorBic],
                                        debtorName = it[debtorName],
                                        currency = it[currency],
                                        creditDebitIndicator = when (it[direction]) {
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
                transaction {
                    val accountLabel = ensureNonNull(call.parameters["label"])
                    val account = getBankAccountFromLabel(accountLabel)
                    val transactionReferenceCrdt = getRandomString(8)
                    val transactionReferenceDbit = getRandomString(8)

                    run {
                        val amount = Random.nextLong(5, 25)
                        BankAccountTransactionsTable.insert {
                            it[creditorIban] = account.iban
                            it[creditorBic] = account.bic
                            it[creditorName] = account.name
                            it[debtorIban] = "DE64500105178797276788"
                            it[debtorBic] = "DEUTDEBB101"
                            it[debtorName] = "Max Mustermann"
                            it[subject] = "sample transaction $transactionReferenceCrdt"
                            it[BankAccountTransactionsTable.amount] = amount.toString()
                            it[currency] = account.currency
                            it[date] = Instant.now().toEpochMilli()
                            it[accountServicerReference] = transactionReferenceCrdt
                            it[BankAccountTransactionsTable.account] = account.id
                            it[direction] = "CRDT"
                        }
                    }

                    run {
                        val amount = Random.nextLong(5, 25)

                        BankAccountTransactionsTable.insert {
                            it[debtorIban] = account.iban
                            it[debtorBic] = account.bic
                            it[debtorName] = account.name
                            it[creditorIban] = "DE64500105178797276788"
                            it[creditorBic] = "DEUTDEBB101"
                            it[creditorName] = "Max Mustermann"
                            it[subject] = "sample transaction $transactionReferenceDbit"
                            it[BankAccountTransactionsTable.amount] = amount.toString()
                            it[currency] = account.currency
                            it[date] = Instant.now().toEpochMilli()
                            it[accountServicerReference] = transactionReferenceDbit
                            it[BankAccountTransactionsTable.account] = account.id
                            it[direction] = "DBIT"
                        }
                    }
                }
                call.respond(object {})
            }
            /**
             * Creates a new Ebics subscriber.
             */
            post("/admin/ebics/subscribers") {
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
            /**
             * Creates a new EBICS host.
             */
            post("/admin/ebics/hosts") {
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
                val ebicsHosts = transaction {
                    EbicsHostEntity.all().map { it.hostId }
                }
                call.respond(EbicsHostsResponse(ebicsHosts))
            }
            /**
             * Serves all the Ebics requests.
             */
            post("/ebicsweb") {
                call.ebicsweb()
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