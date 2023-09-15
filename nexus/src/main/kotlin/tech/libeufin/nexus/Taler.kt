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

package tech.libeufin.nexus

import CamtBankAccountEntry
import TransactionDetails
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.TextContent
import io.ktor.http.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.*
import tech.libeufin.nexus.xlibeufinbank.ingestXLibeufinBankMessage
import tech.libeufin.util.*
import java.net.URL
import kotlin.math.abs
import kotlin.math.min

/**
 * Request body for "$TWG_BASE_URL/transfer".
 */
data class TalerTransferRequest(
    val request_uid: String,
    val amount: String,
    val exchange_base_url: String,
    val wtid: String,
    val credit_account: String // payto://-format
)

data class TalerTransferResponse(
    /**
     * Point in time when Nexus put the payment instruction into the database.
     */
    val timestamp: GnunetTimestamp,
    val row_id: Long
)

/**
 * History accounting data structures, typically
 * used to build JSON responses.
 */
data class TalerIncomingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val debit_account: String,
    val reserve_pub: String
)

data class TalerIncomingHistory(
    var incoming_transactions: MutableList<TalerIncomingBankTransaction> = mutableListOf(),
    val credit_account: String
)

data class TalerOutgoingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val credit_account: String, // payto form,
    val debit_account: String,
    val wtid: String,
    val exchange_base_url: String
)

data class TalerOutgoingHistory(
    var outgoing_transactions: MutableList<TalerOutgoingBankTransaction> = mutableListOf()
)

data class GnunetTimestamp(val t_s: Long)

/**
 * Sort query results in descending order for negative deltas, and ascending otherwise.
 */
fun <T : Entity<Long>> SizedIterable<T>.orderTaler(delta: Int): List<T> {
    return if (delta < 0) {
        this.sortedByDescending { it.id }
    } else {
        this.sortedBy { it.id }
    }
}

/** Builds the comparison operator for history entries based on the sign of 'delta'  */
fun getComparisonOperator(delta: Int, start: Long, table: IdTable<Long>): Op<Boolean> {
    return if (delta < 0) {
        Expression.build {
            table.id less start
        }
    } else {
        Expression.build {
            table.id greater start
        }
    }
}

fun expectLong(param: String?, allowNegative: Boolean = false): Long {
    if (param == null) throw badRequest("'$param' is not Long")
    val maybeLong = try { param.toLong() } catch (e: Exception) {
        throw badRequest("'$param' is not Long")
    }
    if (!allowNegative && maybeLong < 0)
        throw badRequest("Not expecting a negative: $param")
    return maybeLong
}

// Helper handling 'start' being optional and its dependence on 'delta'.
fun handleStartArgument(start: String?, delta: Int): Long {
    if (start == null) {
        if (delta >= 0) return -1
        return Long.MAX_VALUE
    }
    return expectLong(start)
}

/**
 * The Taler layer cannot rely on the ktor-internal JSON-converter/responder,
 * because this one adds a "charset" extra information in the Content-Type header
 * that makes the GNUnet JSON parser unhappy.
 *
 * The workaround is to explicitly convert the 'data class'-object into a JSON
 * string (what this function does), and use the simpler respondText method.
 */
fun customConverter(body: Any): String {
    return jacksonObjectMapper().writeValueAsString(body)
}

// Handle a Taler Wire Gateway /transfer request.
private suspend fun talerTransfer(call: ApplicationCall) {
    val transferRequest = call.receive<TalerTransferRequest>()
    val amountObj = parseAmount(transferRequest.amount)
    // FIXME: Right now we only parse the credit_account, should we also validate that it matches our account info?
    // FIXME, another parse happens below; is this really useful here?
    parsePayto(transferRequest.credit_account)
    val facadeId = expectNonNull(call.parameters["fcid"])
    val opaqueRowId = transaction {
        call.request.requirePermission(PermissionQuery("facade", facadeId, "facade.talerwiregateway.transfer"))
        val facade = FacadeEntity.find { FacadesTable.facadeName eq facadeId }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound,
            "Could not find facade '${facadeId}'"
        )
        val creditorData = parsePayto(transferRequest.credit_account)
        /** Checking the UID has the desired characteristics */
        TalerRequestedPaymentEntity.find {
            TalerRequestedPaymentsTable.requestUid eq transferRequest.request_uid
        }.forEach {
            if (
                (it.amount != transferRequest.amount) or
                (it.creditAccount != transferRequest.exchange_base_url) or
                (it.wtid != transferRequest.wtid)
            ) {
                throw NexusError(
                    HttpStatusCode.Conflict,
                    "This uid (${transferRequest.request_uid}) belongs to a different payment already"
                )
            }
        }
        val exchangeBankAccount = getFacadeBankAccount(facadeId)
        val paymentSubject = "${transferRequest.wtid} ${transferRequest.exchange_base_url}"
        val pain001 = addPaymentInitiation(
            Pain001Data(
                creditorIban = creditorData.iban,
                creditorBic = creditorData.bic,
                creditorName = creditorData.receiverName ?: throw NexusError(
                    HttpStatusCode.BadRequest, "Payto did not mention account owner"
                ),
                subject = paymentSubject,
                sum = amountObj.amount,
                currency = amountObj.currency
            ),
            exchangeBankAccount
        )
        logger.debug("Taler requests payment: ${transferRequest.wtid}")
        val row = TalerRequestedPaymentEntity.new {
            this.facade = facade
            preparedPayment = pain001
            exchangeBaseUrl = transferRequest.exchange_base_url
            requestUid = transferRequest.request_uid
            amount = transferRequest.amount
            wtid = transferRequest.wtid
            creditAccount = transferRequest.credit_account
        }
        row.id.value
    }
    return call.respond(
        TextContent(
            customConverter(
                TalerTransferResponse(
                    /**
                     * Normally should point to the next round where the background
                     * routine will send new PAIN.001 data to the bank; work in progress..
                     */
                    timestamp = GnunetTimestamp(System.currentTimeMillis() / 1000L),
                    row_id = opaqueRowId
                )
            ),
            ContentType.Application.Json
        )
    )
}

// Processes new transactions and stores TWG-specific data in
fun talerFilter(
    payment: NexusBankTransactionEntity,
    txDtls: TransactionDetails
) {
    var isInvalid = false // True when pub is invalid or duplicate.
    val subject = txDtls.unstructuredRemittanceInformation ?: throw
            internalServerError("Payment '${payment.accountTransactionId}' has no subject, can't extract reserve pub.")
    val debtorName = txDtls.debtor?.name
    if (debtorName == null) {
        logger.warn("empty debtor name")
        return
    }
    val debtorAcct = txDtls.debtorAccount
    if (debtorAcct == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("empty debtor account")
        return
    }
    val debtorIban = debtorAcct.iban
    if (debtorIban == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("non-iban debtor account")
        return
    }
    val debtorBic = txDtls.debtorAgent?.bic
    if (debtorBic == null) {
        logger.warn("Not allowing transactions missing the BIC.  IBAN and name: ${debtorIban}, $debtorName")
        return
    }
    val reservePub = extractReservePubFromSubject(subject)
    if (reservePub == null) {
        logger.warn("could not find reserve pub in remittance information")
        TalerInvalidIncomingPaymentEntity.new {
            this.payment = payment
            timestampMs = System.currentTimeMillis()
        }
        // Will be paid back by the refund handler.
        return
    }
    // Check if reserve_pub was used already
    val maybeExist = TalerIncomingPaymentEntity.find {
        TalerIncomingPaymentsTable.reservePublicKey eq reservePub
    }.firstOrNull()
    if (maybeExist != null) {
        val msg = "Reserve pub '$reservePub' was used already"
        logger.info(msg)
        isInvalid = true
    }

    if (!CryptoUtil.checkValidEddsaPublicKey(reservePub)) {
        logger.info("invalid public key detected")
        isInvalid = true
    }
    if (isInvalid) {
        TalerInvalidIncomingPaymentEntity.new {
            this.payment = payment
            timestampMs = System.currentTimeMillis()
        }
        // Will be paid back by the refund handler.
        return
    }
    TalerIncomingPaymentEntity.new {
        this.payment = payment
        reservePublicKey = reservePub
        timestampMs = System.currentTimeMillis()
        debtorPaytoUri = buildIbanPaytoUri(
            debtorIban,
            debtorBic,
            debtorName
        )
    }
    val dbTx = TransactionManager.currentOrNull() ?: throw NexusError(
        HttpStatusCode.InternalServerError,
        "talerFilter(): unexpected execution out of a DB transaction"
    )
    // Only supporting Postgres' NOTIFY.
    if (dbTx.isPostgres()) {
        val channelName = buildChannelName(
            NotificationsChannelDomains.LIBEUFIN_TALER_INCOMING,
            payment.bankAccount.iban
        )
        logger.debug("NOTIFYing on domain" +
                " ${NotificationsChannelDomains.LIBEUFIN_TALER_INCOMING}" +
                " for IBAN: ${payment.bankAccount.iban}.  Resulting channel" +
                " name: $channelName.")
        dbTx.postgresNotify(channelName)
    }
}

fun maybeTalerRefunds(bankAccount: NexusBankAccountEntity, lastSeenId: Long) {
    logger.debug(
        "Searching refundable payments of account: ${bankAccount.bankAccountName}," +
                " after last seen transaction id: $lastSeenId"
    )
    transaction {
        TalerInvalidIncomingPaymentsTable.innerJoin(
            NexusBankTransactionsTable,
            { NexusBankTransactionsTable.id },
            { TalerInvalidIncomingPaymentsTable.payment }
        ).select {
            /**
             * Finds Taler-invalid incoming payments that weren't refunded
             * yet and are newer than those processed along the last round.
             */
            TalerInvalidIncomingPaymentsTable.refunded eq false and
                    (NexusBankTransactionsTable.bankAccount eq bankAccount.id.value) and
                    (NexusBankTransactionsTable.id greater lastSeenId)
        }.forEach {
            // For each of them, extracts the wire details to reuse in the refund.
            val paymentData = jacksonObjectMapper().readValue(
                it[NexusBankTransactionsTable.transactionJson],
                CamtBankAccountEntry::class.java
            )
            val batches = paymentData.batches
            if (batches == null) {
                logger.error(
                    "Empty wire details encountered in transaction with" +
                            " AcctSvcrRef: ${paymentData.accountServicerRef}." +
                            " Taler can't refund."
                )
                throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Unexpected void payment, cannot refund"
                )
            }
            val debtorIban = batches[0].batchTransactions[0].details.debtorAccount?.iban
            if (debtorIban == null) {
                logger.error("Could not find a IBAN to refund in transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "IBAN to refund not found")
            }
            val debtorAgent = batches[0].batchTransactions[0].details.debtorAgent
            if (debtorAgent?.bic == null) {
                logger.error("Could not find the BIC of refundable IBAN at transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "BIC to refund not found")
            }
            val debtorName = batches[0].batchTransactions[0].details.debtor?.name
            if (debtorName == null) {
                logger.error("Could not find the owner's name of refundable IBAN at transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "Name to refund not found")
            }
            // FIXME: investigate this amount!
            val amount = batches[0].batchTransactions[0].amount
            NexusAssert(
                it[NexusBankTransactionsTable.creditDebitIndicator] == "CRDT" &&
                        it[NexusBankTransactionsTable.bankAccount] == bankAccount.id,
                "Cannot refund an _outgoing_ payment!"
            )

            // FIXME #7116
            addPaymentInitiation(
                Pain001Data(
                    creditorIban = debtorIban,
                    creditorBic = debtorAgent.bic,
                    creditorName = debtorName,
                    subject = "Taler refund of: ${batches[0].batchTransactions[0].details.unstructuredRemittanceInformation}",
                    sum = amount.value,
                    currency = amount.currency
                ),
                bankAccount // the Exchange bank account.
            )
            logger.debug("Refund of transaction (AcctSvcrRef): ${paymentData.accountServicerRef} got prepared")
            it[TalerInvalidIncomingPaymentsTable.refunded] = true
        }
    }
}

/**
 * Handle a /taler/history/outgoing request.
 */
private suspend fun historyOutgoing(call: ApplicationCall) {
    val facadeId = expectNonNull(call.parameters["fcid"])
    call.request.requirePermission(PermissionQuery("facade", facadeId, "facade.talerwiregateway.history"))
    val param = call.expectUrlParameter("delta")
    val delta: Int = try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val startCmpOp = getComparisonOperator(delta, start, TalerRequestedPaymentsTable)
    /* retrieve database elements */
    val history = TalerOutgoingHistory()
    transaction {
        /** Retrieve all the outgoing payments from the _clean Taler outgoing table_ */
        val subscriberBankAccount = getFacadeBankAccount(facadeId)
        val reqPayments = mutableListOf<TalerRequestedPaymentEntity>()
        val reqPaymentsWithUnconfirmed = TalerRequestedPaymentEntity.find {
            startCmpOp
        }.orderTaler(delta)
        reqPaymentsWithUnconfirmed.forEach {
            if (it.preparedPayment.confirmationTransaction != null) {
                reqPayments.add(it)
            }
        }
        if (reqPayments.isNotEmpty()) {
            reqPayments.subList(0, min(abs(delta), reqPayments.size)).forEach {
                history.outgoing_transactions.add(
                    TalerOutgoingBankTransaction(
                        row_id = it.id.value,
                        amount = it.amount,
                        wtid = it.wtid,
                        date = GnunetTimestamp(it.preparedPayment.preparationDate / 1000L),
                        credit_account = it.creditAccount,
                        debit_account = buildIbanPaytoUri(
                            subscriberBankAccount.iban,
                            subscriberBankAccount.bankCode,
                            subscriberBankAccount.accountHolder,
                        ),
                        exchange_base_url = it.exchangeBaseUrl
                    )
                )
            }
        }
    }
    if (history.outgoing_transactions.size == 0) {
        call.respond(HttpStatusCode.NoContent)
        return
    }
    call.respond(
        status = HttpStatusCode.OK,
        TextContent(customConverter(history), ContentType.Application.Json)
    )
}

// Handle a /taler-wire-gateway/history/incoming request.
private suspend fun historyIncoming(call: ApplicationCall) {
    val facadeId = expectNonNull(call.parameters["fcid"])
    call.request.requirePermission(
        PermissionQuery(
            "facade",
            facadeId,
            "facade.talerwiregateway.history"
        )
    )
    val longPollTimeoutPar = call.parameters["long_poll_ms"]
    val longPollTimeout = if (longPollTimeoutPar != null) {
        val longPollTimeoutValue = try { longPollTimeoutPar.toLong() }
        catch (e: Exception) {
            throw badRequest("long_poll_ms value is invalid")
        }
        longPollTimeoutValue
    } else null
    val param = call.expectUrlParameter("delta")
    val delta: Int = try { param.toInt() } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val facadeBankAccount = getFacadeBankAccount(facadeId)
    val startCmpOp = getComparisonOperator(delta, start, TalerIncomingPaymentsTable)
    val listenHandle: PostgresListenHandle? = if (isPostgres() && longPollTimeout != null) {
        val notificationChannelName = buildChannelName(
            NotificationsChannelDomains.LIBEUFIN_TALER_INCOMING,
            facadeBankAccount.iban
        )
        val handle = PostgresListenHandle(channelName = notificationChannelName)
        handle.postgresListen()
        handle
    } else null

    /**
     * NOTE: the LISTEN command MAY also go inside this transaction,
     * but LISTEN uses a connection other than the one provided by the
     * transaction block.  More facts on the consequences are needed.
     */
    var result: List<TalerIncomingPaymentEntity> = transaction {
        TalerIncomingPaymentEntity.find { startCmpOp }.orderTaler(delta)
    }
    // The request was lucky, unlisten then.
    if (result.isNotEmpty() && listenHandle != null)
        listenHandle.postgresUnlisten()

    // The request was NOT lucky, wait now.
    if (result.isEmpty() && listenHandle != null && longPollTimeout != null) {
        logger.debug("Waiting for NOTIFY on channel ${listenHandle.channelName}," +
                " with timeout: $longPollTimeoutPar ms")
        val notificationArrived = coroutineScope {
            async(Dispatchers.IO) {
                listenHandle.postgresGetNotifications(longPollTimeout)
            }.await()
        }
        if (notificationArrived) {
            /**
             * NOTE: the query can still have zero results despite the
             * notification.  That happens when the 'start' URI param is
             * higher than the ID of the new row in the database.  Not
             * an error.
             */
            result = transaction {
                // addLogger(StdOutSqlLogger)
                TalerIncomingPaymentEntity.find { startCmpOp }.orderTaler(delta)
            }
        }
    }
    /**
     * Whether because of a timeout or a notification or of never slept, here it
     * proceeds to the response (== resultOrWait.first IS EFFECTIVE).
     */
    val maybeNewPayments = result
    val resp = if (maybeNewPayments.isNotEmpty()) {
        val history = TalerIncomingHistory(
            credit_account = buildIbanPaytoUri(
                facadeBankAccount.iban,
                facadeBankAccount.bankCode,
                facadeBankAccount.accountHolder,
            )
        )
        transaction {
            maybeNewPayments.subList(
                0,
                min(abs(delta), maybeNewPayments.size)
            ).forEach {
                history.incoming_transactions.add(
                    TalerIncomingBankTransaction(
                        // Rounded timestamp
                        date = GnunetTimestamp(it.timestampMs / 1000L),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        reserve_pub = it.reservePublicKey,
                        debit_account = it.debtorPaytoUri
                    )
                )
            }
        }
        history
    } else null
    if (resp == null) {
        call.respond(HttpStatusCode.NoContent)
        return
    }
    return call.respond(
        status = HttpStatusCode.OK,
        TextContent(customConverter(resp), ContentType.Application.Json)
    )
}

/**
 * This call proxies /admin/add/incoming to the Sandbox,
 * which is the service keeping the transaction ledger.
 * The credentials are ASSUMED to be exchange/x (user/pass).
 *
 * In the future, a dedicated "add-incoming" facade should
 * be provided, offering the mean to store the credentials
 * at configuration time.
 */
private suspend fun addIncoming(call: ApplicationCall) {
    val facadeId = ensureNonNull(call.parameters["fcid"])
    val currentBody = call.receive<String>()
    val fromDb = transaction {
        val f = FacadeEntity.findByName(facadeId) ?: throw notFound("facade $facadeId not found")
        val facadeState = FacadeStateEntity.find {
            FacadeStateTable.facade eq f.id
        }.firstOrNull() ?: throw internalServerError("facade $facadeId has no state!")
        val conn = NexusBankConnectionEntity.findByName(facadeState.bankConnection) ?: throw internalServerError(
            "state of facade $facadeId has no bank connection!"
        )
        val sandboxUrl = URL(getConnectionPlugin(conn.type).getBankUrl(conn.connectionId))
        // NOTE: the exchange username must be 'exchange', at the Sandbox.
        return@transaction Pair(
            url {
                protocol = URLProtocol(sandboxUrl.protocol, 80)
                host = sandboxUrl.host
                if (sandboxUrl.port != 80)
                    port = sandboxUrl.port
                path(
                    "demobanks",
                    "default",
                    "taler-wire-gateway",
                    "exchange",
                    "admin",
                    "add-incoming"
                )
            }, // first
            facadeState.bankAccount // second
        )
    }
    val client = HttpClient { followRedirects = true }
    val resp = client.post(fromDb.first) {
        setBody(currentBody)
        basicAuth("exchange", "x")
        contentType(ContentType.Application.Json)
        expectSuccess = false
    }
    // Sandbox itself failed.  Responding Bad Gateway because here is a proxy.
    if (resp.status.value.toString().startsWith('5')) {
        logger.error("Sandbox failed with status code: ${resp.status.description}")
        throw badGateway("Sandbox failed at creating the 'admin/add-incoming' payment")
    }
    // Echo back whatever error is left, because that should be the client fault.
    if (!resp.status.value.toString().startsWith('2')) {
        logger.error("Client-side error for /admin/add-incoming.  Sandbox says: ${resp.bodyAsText()}")
        call.respond(resp.status, resp.bodyAsText())
    }
    // x-libeufin-bank-ingest
    val ingestionResult = ingestXLibeufinBankMessage(
        fromDb.second,
        resp.bodyAsText()
    )
    if (ingestionResult.newTransactions != 1)
        throw internalServerError("/admin/add-incoming was ingested into ${ingestionResult.newTransactions} new transactions, but it must have one.")
    if (ingestionResult.errors != null) {
        val errors = ingestionResult.errors
        errors?.forEach {
            logger.error(it.message)
        }
        throw internalServerError("/admin/add-incoming ingestion failed.")
    }
    // TWG ingest.
    ingestFacadeTransactions(
        bankAccountId = fromDb.second,
        facadeType = NexusFacadeType.TALER,
        incomingFilterCb = ::talerFilter,
        refundCb = ::maybeTalerRefunds
    )
    /**
     * The latest incoming payment should now be found among
     * the ingested ones.
     */
    val lastIncomingPayment = transaction {
        val allIncomingPayments = TalerIncomingPaymentEntity.all()
        /**
         * One payment must appear, since it was created BY this handler.
         * If not, then respond 500.
         */
        if (allIncomingPayments.empty())
            throw internalServerError("Incoming payment(s) not found AFTER /add-incoming")
        val lastRecord = allIncomingPayments.last()
        return@transaction Pair(lastRecord.id.value, lastRecord.timestampMs)
    }
    call.respond(object {
        val row_id = lastIncomingPayment.first
        val timestamp = GnunetTimestamp(lastIncomingPayment.second / 1000L)
    })
}

private fun getCurrency(facadeName: String): String {
    return transaction {
        getFacadeState(facadeName).currency
    }
}

fun talerFacadeRoutes(route: Route) {
    route.get("/config") {
        val facadeId = ensureNonNull(call.parameters["fcid"])
        call.request.requirePermission(
            PermissionQuery("facade", facadeId, "facade.talerwiregateway.transfer"),
            PermissionQuery("facade", facadeId, "facade.talerwiregateway.history")
        )
        call.respond(object {
            val version = "0:0:0"
            val name = "taler-wire-gateway"
            val currency = getCurrency(facadeId)
        })
        return@get
    }
    route.post("/transfer") {
        talerTransfer(call)
        return@post
    }
    route.get("/history/outgoing") {
        historyOutgoing(call)
        return@get
    }
    route.get("/history/incoming") {
        historyIncoming(call)
        return@get
    }
    route.post("/admin/add-incoming") {
        addIncoming(call)
        return@post
    }
    route.get("") {
        call.respondText("Hello, this is a Taler Facade")
        return@get
    }
}
