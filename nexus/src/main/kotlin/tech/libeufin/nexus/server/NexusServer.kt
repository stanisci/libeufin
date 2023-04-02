/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus.server

import UtilError
import io.ktor.serialization.jackson.*
import io.ktor.server.plugins.contentnegotiation.*
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.*
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.event.Level
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.*
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.iso20022.CamtBankAccountEntry
import tech.libeufin.nexus.iso20022.processCamtMessage
import tech.libeufin.util.*
import java.net.BindException
import java.net.URLEncoder
import kotlin.system.exitProcess

// Return facade state depending on the type.
fun getFacadeState(type: String, facade: FacadeEntity): JsonNode {
    return transaction {
        when (type) {
            "taler-wire-gateway",
            "anastasis" -> {
                val state = FacadeStateEntity.find {
                    FacadeStateTable.facade eq facade.id
                }.firstOrNull()
                if (state == null) throw NexusError(
                    HttpStatusCode.NotFound,
                    "State of facade ${facade.id} not found"
                )
                val node = jacksonObjectMapper().createObjectNode()
                node.put("bankConnection", state.bankConnection)
                node.put("bankAccount", state.bankAccount)
                node
            }
            else -> throw NexusError(
                HttpStatusCode.NotFound,
                "Facade type $type not supported"
            )
        }
    }
}


fun ensureNonNull(param: String?): String {
    return param ?: throw NexusError(
        HttpStatusCode.BadRequest, "Bad ID given: $param"
    )
}

fun ensureLong(param: String?): Long {
    val asString = ensureNonNull(param)
    return asString.toLongOrNull() ?: throw NexusError(
        HttpStatusCode.BadRequest, "Parameter is not Long: $param"
    )
}

fun <T> expectNonNull(param: T?): T {
    return param ?: throw NexusError(
        HttpStatusCode.BadRequest,
        "Non-null value expected."
    )
}


fun ApplicationRequest.hasBody(): Boolean {
    if (this.isChunked()) {
        return true
    }
    val contentLengthHeaderStr = this.headers["content-length"]
    if (contentLengthHeaderStr != null) {
        return try {
            val cl = contentLengthHeaderStr.toInt()
            cl != 0
        } catch (e: NumberFormatException) {
            false
        }
    }
    return false
}

fun ApplicationCall.expectUrlParameter(name: String): String {
    return this.request.queryParameters[name]
        ?: throw NexusError(HttpStatusCode.BadRequest, "Parameter '$name' not provided in URI")
}

fun requireBankConnectionInternal(connId: String): NexusBankConnectionEntity {
    return transaction {
        NexusBankConnectionEntity.find { NexusBankConnectionsTable.connectionId eq connId }.firstOrNull()
    }
        ?: throw NexusError(HttpStatusCode.NotFound, "bank connection '$connId' not found")
}

fun requireBankConnection(call: ApplicationCall, parameterKey: String): NexusBankConnectionEntity {
    val name = call.parameters[parameterKey]
    if (name == null) {
        throw NexusError(
            HttpStatusCode.NotFound,
            "Parameter '${parameterKey}' wasn't found in URI"
        )
    }
    return requireBankConnectionInternal(name)
}

val client = HttpClient { followRedirects = true }
val nexusApp: Application.() -> Unit = {
    install(CallLogging) {
        this.level = Level.DEBUG
        this.logger = tech.libeufin.nexus.logger
    }
    install(LibeufinDecompressionPlugin)
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
                indentObjectsWith(DefaultIndenter("  ", "\n"))
            })
            registerModule(
                KotlinModule.Builder()
                    .withReflectionCacheSize(512)
                    .configure(KotlinFeature.NullToEmptyCollection, false)
                    .configure(KotlinFeature.NullToEmptyMap, false)
                    .configure(KotlinFeature.NullIsSameAsDefault, enabled = true)
                    .configure(KotlinFeature.SingletonSupport, enabled = false)
                    .configure(KotlinFeature.StrictNullChecks, false)
                    .build()
            )
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }
    install(StatusPages) {
        exception<NexusError> { call, cause ->
            logger.error("Caught exception while handling '${call.request.uri} (${cause.message})")
            call.respond(
                status = cause.statusCode,
                message = ErrorResponse(
                    code = TalerErrorCode.TALER_EC_LIBEUFIN_NEXUS_GENERIC_ERROR.code,
                    hint = "nexus error, see detail",
                    detail = cause.reason,
                )
            )
        }
        exception<JsonMappingException> { call, cause ->
            logger.error("Exception while handling '${call.request.uri}'", cause.message)
            call.respond(
                HttpStatusCode.BadRequest,
                message = ErrorResponse(
                    code = TalerErrorCode.TALER_EC_GENERIC_JSON_INVALID.code,
                    hint = "POSTed data was not valid",
                    detail = cause.message ?: "not given",
                )
            )
        }
        exception<UtilError> { call, cause ->
            logger.error("Exception while handling '${call.request.uri}': ${cause.message}")
            call.respond(
                cause.statusCode,
                message = ErrorResponse(
                    code = cause.ec?.code ?: TalerErrorCode.TALER_EC_NONE.code,
                    hint = "see detail",
                    detail = cause.reason,
                )
            )
        }
        exception<EbicsProtocolError> { call, cause ->
            logger.error("Caught exception while handling '${call.request.uri}' (${cause.message})")
            call.respond(
                cause.httpStatusCode,
                message = ErrorResponse(
                    code = TalerErrorCode.TALER_EC_LIBEUFIN_NEXUS_GENERIC_ERROR.code,
                    hint = "The EBICS communication with the bank failed: ${cause.ebicsTechnicalCode}",
                    detail = cause.reason,
                )
            )
        }
        exception<BadRequestException> { call, wrapper ->
            var rootCause = wrapper.cause
            while (rootCause?.cause != null) rootCause = rootCause.cause
            val errorMessage: String? = rootCause?.message ?: wrapper.message
            if (errorMessage == null) {
                logger.error("The bank didn't detect the cause of a bad request, fail.")
                logger.error(wrapper.stackTraceToString())
                throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Did not find bad request details."
                )
            }
            logger.error(errorMessage)
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = TalerErrorCode.TALER_EC_LIBEUFIN_NEXUS_GENERIC_ERROR.code,
                    detail = errorMessage,
                    hint = "Malformed request or unacceptable values"
                )
            )
        }
        exception<Exception> { call, cause ->
            logger.error(
                "Uncaught exception while handling '${call.request.uri}'",
                cause.stackTraceToString()
            )
            cause.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = TalerErrorCode.TALER_EC_LIBEUFIN_NEXUS_UNCAUGHT_EXCEPTION.code,
                    hint = "unexpected exception",
                    detail = "exception message: ${cause.message}",
                )
            )
        }
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (this.call.response.status() == null) {
            call.respondText("Not found (no route matched).\n", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@intercept finish()
        }
    }
    routing {
        get("/config") {
            call.respond(
                makeJsonObject {
                    prop("version", getVersion())
                }
            )
            return@get
        }
        // Shows information about the requesting user.
        get("/user") {
            val ret = transaction {
                val currentUser = authenticateRequest(call.request)
                UserResponse(
                    username = currentUser.username,
                    superuser = currentUser.superuser
                )
            }
            call.respond(ret)
            return@get
        }

        get("/permissions") {
            val resp = object {
                val permissions = mutableListOf<Permission>()
            }
            transaction {
                requireSuperuser(call.request)
                NexusPermissionEntity.all().map {
                    resp.permissions.add(
                        Permission(
                            subjectType = it.subjectType,
                            subjectId = it.subjectId,
                            resourceType = it.resourceType,
                            resourceId = it.resourceId,
                            permissionName = it.permissionName,
                        )
                    )
                }
            }
            call.respond(resp)
        }

        post("/permissions") {
            val req = call.receive<ChangePermissionsRequest>()
            val knownPermissions = listOf(
                "facade.talerwiregateway.history", "facade.talerwiregateway.transfer",
                "facade.anastasis.history"
            )
            val permName = req.permission.permissionName.lowercase()
            if (!knownPermissions.contains(permName)) {
                throw NexusError(
                    HttpStatusCode.BadRequest,
                    "Permission $permName not known"
                )
            }
            transaction {
                requireSuperuser(call.request)
                val existingPerm = findPermission(req.permission)
                when (req.action) {
                    PermissionChangeAction.GRANT -> {
                        if (existingPerm == null) {
                            NexusPermissionEntity.new {
                                subjectType = req.permission.subjectType
                                subjectId = req.permission.subjectId
                                resourceType = req.permission.resourceType
                                resourceId = req.permission.resourceId
                                permissionName = permName

                            }
                        }
                    }
                    PermissionChangeAction.REVOKE -> {
                        existingPerm?.delete()
                    }
                }
                null
            }
            call.respond(object {})
        }

        get("/users") {
            transaction {
                requireSuperuser(call.request)
            }
            val users = transaction {
                transaction {
                    NexusUserEntity.all().map {
                        UserInfo(it.username, it.superuser)
                    }
                }
            }
            val usersResp = UsersResponse(users)
            call.respond(usersResp)
            return@get
        }

        // change a user's password
        post("/users/{username}/password") {
            val body = call.receive<ChangeUserPassword>()
            val targetUsername = ensureNonNull(call.parameters["username"])
            transaction {
                requireSuperuser(call.request)
                val targetUser = NexusUserEntity.find {
                    NexusUsersTable.username eq targetUsername
                }.firstOrNull()
                if (targetUser == null) throw NexusError(
                    HttpStatusCode.NotFound,
                    "Username $targetUsername not found"
                )
                targetUser.passwordHash = CryptoUtil.hashpw(body.newPassword)
            }
            call.respond(NexusMessage(message = "Password successfully changed"))
            return@post
        }

        // Add a new ordinary user in the system (requires superuser privileges)
        post("/users") {
            requireSuperuser(call.request)
            val body = call.receive<CreateUserRequest>()
            val requestedUsername = requireValidResourceName(body.username)
            transaction {
                // check if username is available
                val checkUsername = NexusUserEntity.find {
                    NexusUsersTable.username eq requestedUsername
                }.firstOrNull()
                if (checkUsername != null) throw NexusError(
                    HttpStatusCode.Conflict,
                    "Username $requestedUsername unavailable"
                )
                NexusUserEntity.new {
                    username = requestedUsername
                    passwordHash = CryptoUtil.hashpw(body.password)
                    superuser = false
                }
            }
            call.respond(
                NexusMessage(
                    message = "New user '${body.username}' registered"
                )
            )
            return@post
        }

        get("/bank-connection-protocols") {
            requireSuperuser(call.request)
            call.respond(
                HttpStatusCode.OK,
                BankProtocolsResponse(listOf("ebics"))
            )
            return@get
        }

        route("/bank-connection-protocols/ebics") {
            ebicsBankProtocolRoutes(client)
        }

        // Shows the bank accounts belonging to the requesting user.
        get("/bank-accounts") {
            requireSuperuser(call.request)
            val bankAccounts = BankAccounts()
            transaction {
                NexusBankAccountEntity.all().forEach {
                    bankAccounts.accounts.add(
                        BankAccount(
                            ownerName = it.accountHolder,
                            iban = it.iban,
                            bic = it.bankCode,
                            nexusBankAccountId = it.bankAccountName
                        )
                    )
                }
            }
            call.respond(bankAccounts)
            return@get
        }
        post("/bank-accounts/{accountId}/test-camt-ingestion/{type}") {
            requireSuperuser(call.request)
            processCamtMessage(
                ensureNonNull(call.parameters["accountId"]),
                XMLUtil.parseStringIntoDom(call.receiveText()),
                ensureNonNull(call.parameters["type"])
            )
            call.respond(object {})
            return@post
        }
        get("/bank-accounts/{accountId}/schedule") {
            requireSuperuser(call.request)
            val resp = jacksonObjectMapper().createObjectNode()
            val ops = jacksonObjectMapper().createObjectNode()
            val accountId = ensureNonNull(call.parameters["accountId"])
            resp.set<JsonNode>("schedule", ops)
            transaction {
                NexusBankAccountEntity.findByName(accountId)
                    ?: throw unknownBankAccount(accountId)
                NexusScheduledTaskEntity.find {
                    (NexusScheduledTasksTable.resourceType eq "bank-account") and
                            (NexusScheduledTasksTable.resourceId eq accountId)

                }.forEach {
                    val t = jacksonObjectMapper().createObjectNode()
                    ops.set<JsonNode>(it.taskName, t)
                    t.put("cronspec", it.taskCronspec)
                    t.put("type", it.taskType)
                    t.set<JsonNode>("params", jacksonObjectMapper().readTree(it.taskParams))
                }
            }
            call.respond(resp)
            return@get
        }

        post("/bank-accounts/{accountId}/schedule") {
            requireSuperuser(call.request)
            val schedSpec = call.receive<CreateAccountTaskRequest>()
            val accountId = ensureNonNull(call.parameters["accountId"])
            transaction {
                NexusBankAccountEntity.findByName(accountId)
                    ?: throw unknownBankAccount(accountId)
                try {
                    NexusCron.parser.parse(schedSpec.cronspec)
                } catch (e: IllegalArgumentException) {
                    throw NexusError(HttpStatusCode.BadRequest, "bad cron spec: ${e.message}")
                }
                // sanity checks.
                when (schedSpec.type) {
                    "fetch" -> {
                        jacksonObjectMapper().treeToValue(schedSpec.params, FetchSpecJson::class.java)
                            ?: throw NexusError(HttpStatusCode.BadRequest, "bad fetch spec")
                    }
                    "submit" -> {
                    }
                    else -> throw NexusError(HttpStatusCode.BadRequest, "unsupported task type")
                }
                val oldSchedTask = NexusScheduledTaskEntity.find {
                    (NexusScheduledTasksTable.taskName eq schedSpec.name) and
                            (NexusScheduledTasksTable.resourceType eq "bank-account") and
                            (NexusScheduledTasksTable.resourceId eq accountId)

                }.firstOrNull()
                if (oldSchedTask != null) {
                    throw NexusError(HttpStatusCode.BadRequest, "schedule task already exists")
                }
                NexusScheduledTaskEntity.new {
                    resourceType = "bank-account"
                    resourceId = accountId
                    this.taskCronspec = schedSpec.cronspec
                    this.taskName = requireValidResourceName(schedSpec.name)
                    this.taskType = schedSpec.type
                    this.taskParams =
                        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schedSpec.params)
                }
            }
            call.respond(object {})
            return@post
        }

        get("/bank-accounts/{accountId}/schedule/{taskId}") {
            requireSuperuser(call.request)
            val taskId = ensureNonNull(call.parameters["taskId"])
            val task = transaction {
                NexusScheduledTaskEntity.find {
                    NexusScheduledTasksTable.taskName eq taskId
                }.firstOrNull()
            }
            if (task == null) throw NexusError(HttpStatusCode.NotFound, "Task ${taskId} wasn't found")
            call.respond(
                AccountTask(
                    resourceId = task.resourceId,
                    resourceType = task.resourceType,
                    taskName = task.taskName,
                    taskCronspec = task.taskCronspec,
                    taskType = task.taskType,
                    taskParams = task.taskParams,
                    nextScheduledExecutionSec = task.nextScheduledExecutionSec,
                    prevScheduledExecutionSec = task.prevScheduledExecutionSec
                )
            )
            return@get
        }

        delete("/bank-accounts/{accountId}/schedule/{taskId}") {
            requireSuperuser(call.request)
            logger.info("schedule delete requested")
            val accountId = ensureNonNull(call.parameters["accountId"])
            val taskId = ensureNonNull(call.parameters["taskId"])
            transaction {
                val bankAccount = NexusBankAccountEntity.findByName(accountId)
                if (bankAccount == null) {
                    throw unknownBankAccount(accountId)
                }
                val oldSchedTask = NexusScheduledTaskEntity.find {
                    (NexusScheduledTasksTable.taskName eq taskId) and
                            (NexusScheduledTasksTable.resourceType eq "bank-account") and
                            (NexusScheduledTasksTable.resourceId eq accountId)

                }.firstOrNull()
                if (oldSchedTask == null)
                    throw notFound("Task $taskId is not found.")
                oldSchedTask.delete()
            }
            call.respond(object {})
        }

        get("/bank-accounts/{accountid}") {
            requireSuperuser(call.request)
            val accountId = ensureNonNull(call.parameters["accountid"])
            val res = transaction {
                val bankAccount = NexusBankAccountEntity.findByName(accountId)
                if (bankAccount == null) {
                    throw unknownBankAccount(accountId)
                }
                val holderEnc = URLEncoder.encode(bankAccount.accountHolder, Charsets.UTF_8)
                val lastSeenBalance = NexusBankBalanceEntity.find {
                    NexusBankBalancesTable.bankAccount eq bankAccount.id
                }.lastOrNull()
                return@transaction makeJsonObject {
                    prop("defaultBankConnection", bankAccount.defaultBankConnection?.id?.value)
                    prop("accountPaytoUri", "payto://iban/${bankAccount.iban}?receiver-name=$holderEnc")
                    prop(
                        "lastSeenBalance",
                        if (lastSeenBalance != null) {
                            val sign = if (lastSeenBalance.creditDebitIndicator == "DBIT") "-" else ""
                            "${sign}${lastSeenBalance.balance}"
                        } else {
                            "not downloaded from the bank yet"
                        }
                    )
                }
            }
            call.respond(res)
        }

        // Submit one particular payment to the bank.
        post("/bank-accounts/{accountid}/payment-initiations/{uuid}/submit") {
            requireSuperuser(call.request)
            val uuid = ensureLong(call.parameters["uuid"])
            submitPaymentInitiation(client, uuid)
            call.respondText("Payment $uuid submitted")
            return@post
        }

        post("/bank-accounts/{accountid}/submit-all-payment-initiations") {
            requireSuperuser(call.request)
            val accountId = ensureNonNull(call.parameters["accountid"])
            submitAllPaymentInitiations(client, accountId)
            call.respond(object {})
            return@post
        }

        get("/bank-accounts/{accountid}/payment-initiations") {
            requireSuperuser(call.request)
            val ret = InitiatedPayments()
            transaction {
                val bankAccount = requireBankAccount(call, "accountid")
                PaymentInitiationEntity.find {
                    PaymentInitiationsTable.bankAccount eq bankAccount.id.value
                }.forEach {
                    val sd = it.submissionDate
                    ret.initiatedPayments.add(
                        PaymentStatus(
                            status = it.confirmationTransaction?.status,
                            paymentInitiationId = it.id.value.toString(),
                            submitted = it.submitted,
                            creditorIban = it.creditorIban,
                            creditorName = it.creditorName,
                            creditorBic = it.creditorBic,
                            amount = "${it.currency}:${it.sum}",
                            subject = it.subject,
                            submissionDate = if (sd != null) {
                                importDateFromMillis(sd).toDashedDate()
                            } else null,
                            preparationDate = importDateFromMillis(it.preparationDate).toDashedDate()
                        )
                    )
                }
            }
            call.respond(ret)
            return@get
        }

        // Shows information about one particular payment initiation.
        get("/bank-accounts/{accountid}/payment-initiations/{uuid}") {
            requireSuperuser(call.request)
            val res = transaction {
                val paymentInitiation = getPaymentInitiation(ensureLong(call.parameters["uuid"]))
                return@transaction object {
                    val paymentInitiation = paymentInitiation
                    val paymentStatus = paymentInitiation.confirmationTransaction?.status
                }
            }
            val sd = res.paymentInitiation.submissionDate
            call.respond(
                PaymentStatus(
                    paymentInitiationId = res.paymentInitiation.id.value.toString(),
                    submitted = res.paymentInitiation.submitted,
                    creditorName = res.paymentInitiation.creditorName,
                    creditorBic = res.paymentInitiation.creditorBic,
                    creditorIban = res.paymentInitiation.creditorIban,
                    amount = "${res.paymentInitiation.currency}:${res.paymentInitiation.sum}",
                    subject = res.paymentInitiation.subject,
                    submissionDate = if (sd != null) {
                        importDateFromMillis(sd).toDashedDate()
                    } else null,
                    status = res.paymentStatus,
                    preparationDate = importDateFromMillis(res.paymentInitiation.preparationDate).toDashedDate()
                )
            )
            return@get
        }

        delete("/bank-accounts/{accountId}/payment-initiations/{uuid}") {
            requireSuperuser(call.request)
            val uuid = ensureLong(call.parameters["uuid"])
            transaction {
                val paymentInitiation = getPaymentInitiation(uuid)
                paymentInitiation.delete()
            }
            call.respond(NexusMessage(message = "Payment initiation $uuid deleted"))
        }

        // Adds a new payment initiation.
        post("/bank-accounts/{accountid}/payment-initiations") {
            requireSuperuser(call.request)
            val body = call.receive<CreatePaymentInitiationRequest>()
            val accountId = ensureNonNull(call.parameters["accountid"])
            if (!validateBic(body.bic)) {
                throw NexusError(HttpStatusCode.BadRequest, "invalid BIC (${body.bic})")
            }
            val res = transaction {
                val bankAccount = NexusBankAccountEntity.findByName(accountId)
                if (bankAccount == null) {
                    throw unknownBankAccount(accountId)
                }
                val amount = parseAmount(body.amount)
                val paymentEntity = addPaymentInitiation(
                    Pain001Data(
                        creditorIban = body.iban,
                        creditorBic = body.bic,
                        creditorName = body.name,
                        sum = amount.amount,
                        currency = amount.currency,
                        subject = body.subject
                    ),
                    bankAccount
                )
                return@transaction object {
                    val uuid = paymentEntity.id.value
                }
            }
            call.respond(
                HttpStatusCode.OK,
                PaymentInitiationResponse(uuid = res.uuid.toString())
            )
            return@post
        }

        // Downloads new transactions from the bank.
        post("/bank-accounts/{accountid}/fetch-transactions") {
            requireSuperuser(call.request)
            val accountid = call.parameters["accountid"]
            if (accountid == null) {
                throw NexusError(
                    HttpStatusCode.BadRequest,
                    "Account id missing"
                )
            }
            val fetchSpec = if (call.request.hasBody()) {
                call.receive<FetchSpecJson>()
            } else {
                FetchSpecLatestJson(
                    FetchLevel.STATEMENT,
                    null
                )
            }
            val ingestionResult = fetchBankAccountTransactions(client, fetchSpec, accountid)
            var statusCode = HttpStatusCode.OK
            /**
             * Client errors are unlikely here, because authentication
             * and JSON validity fail earlier.  Hence, either Nexus or the
             * bank had a problem.  NOTE: because this handler triggers multiple
             * fetches, it is ALSO possible that although one error is reported,
             * SOME transactions made it to the database!
             */
            if (ingestionResult.errors != null)
            /**
             * Nexus could not handle the error (regardless of it being generated
             * here or gotten from the bank).  The response body should inform the
             * client about what failed.
             */
            statusCode = HttpStatusCode.InternalServerError
            call.respond(
                status = statusCode,
                ingestionResult
            )
            return@post
        }

        // Asks list of transactions ALREADY downloaded from the bank.
        get("/bank-accounts/{accountid}/transactions") {
            requireSuperuser(call.request)
            val bankAccountId = expectNonNull(call.parameters["accountid"])
            val ret = Transactions()
            transaction {
                val bankAccount = NexusBankAccountEntity.findByName(bankAccountId)
                if (bankAccount == null) {
                    throw unknownBankAccount(bankAccountId)
                }
                NexusBankTransactionEntity.find { NexusBankTransactionsTable.bankAccount eq bankAccount.id }.map {
                    val tx = jacksonObjectMapper().readValue(
                        it.transactionJson, CamtBankAccountEntry::class.java
                    )
                    ret.transactions.add(tx)
                }
            }
            call.respond(ret)
            return@get
        }

        // Adds a new bank transport.
        post("/bank-connections") {
            requireSuperuser(call.request)
            // user exists and is authenticated.
            val body = call.receive<CreateBankConnectionRequestJson>()
            requireValidResourceName(body.name)
            transaction {
                val user = authenticateRequest(call.request)
                val existingConn =
                    NexusBankConnectionEntity.find { NexusBankConnectionsTable.connectionId eq body.name }
                        .firstOrNull()
                if (existingConn != null) {
                    // FIXME: make idempotent.
                    throw NexusError(HttpStatusCode.Conflict, "connection '${body.name}' exists already")
                }
                when (body) {
                    is CreateBankConnectionFromBackupRequestJson -> {
                        val type = body.data.get("type")
                        if (type == null || !type.isTextual) {
                            throw NexusError(HttpStatusCode.BadRequest, "backup needs type")
                        }
                        val plugin = getConnectionPlugin(type.textValue())
                        plugin.createConnectionFromBackup(body.name, user, body.passphrase, body.data)
                    }
                    is CreateBankConnectionFromNewRequestJson -> {
                        val plugin = getConnectionPlugin(body.type)
                        plugin.createConnection(body.name, user, body.data)
                    }
                }
            }
            call.respond(object {})
        }

        post("/bank-connections/delete-connection") {
            requireSuperuser(call.request)
            val body = call.receive<BankConnectionDeletion>()
            transaction {
                val conn =
                    NexusBankConnectionEntity.find { NexusBankConnectionsTable.connectionId eq body.bankConnectionId }
                        .firstOrNull() ?: throw NexusError(
                        HttpStatusCode.NotFound,
                        "Bank connection ${body.bankConnectionId}"
                    )
                conn.delete() // temporary, and instead just _mark_ it as deleted?
            }
            call.respond(object {})
        }

        get("/bank-connections") {
            requireSuperuser(call.request)
            val connList = BankConnectionsList()
            transaction {
                NexusBankConnectionEntity.all().forEach {
                    connList.bankConnections.add(
                        BankConnectionInfo(
                            name = it.connectionId,
                            type = it.type
                        )
                    )
                }
            }
            call.respond(connList)
        }

        get("/bank-connections/{connectionName}") {
            requireSuperuser(call.request)
            val resp = transaction {
                val conn = requireBankConnection(call, "connectionName")
                getConnectionPlugin(conn.type).getConnectionDetails(conn)
            }
            call.respond(resp)
        }

        post("/bank-connections/{connectionName}/export-backup") {
            requireSuperuser(call.request)
            val body = call.receive<BackupRequestJson>()
            val response = run {
                val conn = requireBankConnection(call, "connectionName")
                getConnectionPlugin(conn.type).exportBackup(conn.connectionId, body.passphrase)
            }
            call.response.headers.append("Content-Disposition", "attachment")
            call.respond(
                HttpStatusCode.OK,
                response
            )
        }

        post("/bank-connections/{connectionName}/connect") {
            requireSuperuser(call.request)
            val conn = transaction {
                requireBankConnection(call, "connectionName")
            }
            val plugin = getConnectionPlugin(conn.type)
            plugin.connect(client, conn.connectionId)
            call.respond(NexusMessage(message = "Connection successful"))
        }

        get("/bank-connections/{connectionName}/keyletter") {
            requireSuperuser(call.request)
            val conn = transaction {
                requireBankConnection(call, "connectionName")
            }
            val pdfBytes = getConnectionPlugin(conn.type).exportAnalogDetails(conn)
            call.respondBytes(pdfBytes, ContentType("application", "pdf"))
        }

        get("/bank-connections/{connectionName}/messages") {
            requireSuperuser(call.request)
            val ret = transaction {
                val list = BankMessageList()
                val conn = requireBankConnection(call, "connectionName")
                NexusBankMessageEntity.find { NexusBankMessagesTable.bankConnection eq conn.id }.map {
                    list.bankMessages.add(
                        BankMessageInfo(
                            messageId = it.messageId,
                            code = it.code,
                            length = it.message.bytes.size.toLong()
                        )
                    )
                }
                list
            }
            call.respond(ret)
        }

        get("/bank-connections/{connid}/messages/{msgid}") {
            requireSuperuser(call.request)
            val ret = transaction {
                val msgid = call.parameters["msgid"]
                if (msgid == null || msgid == "") {
                    throw NexusError(HttpStatusCode.BadRequest, "missing or invalid message ID")
                }
                val msg = NexusBankMessageEntity.find { NexusBankMessagesTable.messageId eq msgid }.firstOrNull()
                    ?: throw NexusError(HttpStatusCode.NotFound, "bank message not found")
                return@transaction object {
                    val msgContent = msg.message.bytes
                }
            }
            call.respondBytes(ret.msgContent, ContentType("application", "xml"))
        }

        get("/facades/{fcid}") {
            requireSuperuser(call.request)
            val fcid = ensureNonNull(call.parameters["fcid"])
            val ret = transaction {
                val f = FacadeEntity.findByName(fcid) ?: throw NexusError(
                    HttpStatusCode.NotFound, "Facade $fcid does not exist"
                )
                // FIXME: this only works for TWG urls.
                FacadeShowInfo(
                    name = f.facadeName,
                    type = f.type,
                    baseUrl = URLBuilder(call.request.getBaseUrl()).apply {
                        this.appendPathSegments(listOf("facades", f.facadeName, f.type))
                        encodedPath += "/"
                    }.buildString(),
                    config = getFacadeState(f.type, f)
                )
            }
            call.respond(ret)
            return@get
        }

        get("/facades") {
            requireSuperuser(call.request)
            val ret = object {
                val facades = mutableListOf<FacadeShowInfo>()
            }
            transaction {
                val user = authenticateRequest(call.request)
                FacadeEntity.find {
                    FacadesTable.creator eq user.id
                }.forEach {
                    ret.facades.add(
                        FacadeShowInfo(
                            name = it.facadeName,
                            type = it.type,
                            baseUrl = URLBuilder(call.request.getBaseUrl()).apply {
                                this.appendPathSegments(listOf("facades", it.facadeName, it.type))
                                encodedPath += "/"
                            }.buildString(),
                            config = getFacadeState(it.type, it)
                        )
                    )
                }
            }
            call.respond(ret)
            return@get
        }

        delete("/facades/{fcid}") {
            requireSuperuser(call.request)
            val fcid = ensureNonNull(call.parameters["fcid"])
            transaction {
                val f = FacadeEntity.findByName(fcid) ?: throw NexusError(
                    HttpStatusCode.NotFound,
                    "Facade $fcid does not exist"
                )
                f.delete()
            }
            call.respond({})
            return@delete
        }

        post("/facades") {
            requireSuperuser(call.request)
            val body = call.receive<FacadeInfo>()
            requireValidResourceName(body.name)
            if (!listOf("taler-wire-gateway", "anastasis").contains(body.type))
                throw NexusError(
                    HttpStatusCode.NotImplemented,
                    "Facade type '${body.type}' is not implemented"
                )
            try {
                transaction {
                    val user = authenticateRequest(call.request)
                    val newFacade = FacadeEntity.new {
                        facadeName = body.name
                        type = body.type
                        creator = user
                    }
                    FacadeStateEntity.new {
                        bankAccount = body.config.bankAccount
                        bankConnection = body.config.bankConnection
                        reserveTransferLevel = body.config.reserveTransferLevel
                        facade = newFacade
                        currency = body.config.currency
                    }
                }
            } catch (e: Exception) {
                logger.error(e.stackTraceToString())
                throw internalServerError("Could not create facade")
            }
            call.respond(HttpStatusCode.OK)
            return@post
        }

        route("/bank-connections/{connid}") {

            // only ebics specific tasks under this part.
            route("/ebics") {
                ebicsBankConnectionRoutes(client)
            }
            post("/fetch-accounts") {
                requireSuperuser(call.request)
                val conn = transaction {
                    requireBankConnection(call, "connid")
                }
                getConnectionPlugin(conn.type).fetchAccounts(client, conn.connectionId)
                call.respond(object {})
            }

            // show all the offered accounts (both imported and non)
            get("/accounts") {
                requireSuperuser(call.request)
                val ret = OfferedBankAccounts()
                transaction {
                    val conn = requireBankConnection(call, "connid")
                    OfferedBankAccountEntity.find {
                        OfferedBankAccountsTable.bankConnection eq conn.id.value
                    }.forEach { offeredAccount ->
                        val importedId = offeredAccount.imported?.id
                        val imported = if (importedId != null) {
                            NexusBankAccountEntity.findById(importedId)
                        } else {
                            null
                        }
                        ret.accounts.add(
                            OfferedBankAccount(
                                ownerName = offeredAccount.accountHolder,
                                iban = offeredAccount.iban,
                                bic = offeredAccount.bankCode,
                                offeredAccountId = offeredAccount.offeredAccountId,
                                nexusBankAccountId = imported?.bankAccountName
                            )
                        )
                    }
                }
                call.respond(ret)
            }

            // import one account into libeufin.
            post("/import-account") {
                requireSuperuser(call.request)
                val body = call.receive<ImportBankAccount>()
                importBankAccount(call, body.offeredAccountId, body.nexusBankAccountId)
                call.respond(object {})
            }
        }
        route("/facades/{fcid}/taler-wire-gateway") {
            talerFacadeRoutes(this)
        }
        route("/facades/{fcid}/anastasis") {
            anastasisFacadeRoutes(this)
        }

        // Hello endpoint.
        get("/") {
            call.respondText("Hello, this is Nexus.\n")
            return@get
        }
    }
}
