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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.content.TextContent
import io.ktor.http.*
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.util.*
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.iso20022.*
import tech.libeufin.nexus.server.*
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
 * History accounting data structures
 */
data class TalerIncomingBankTransaction(
    val row_id: Long,
    val date: GnunetTimestamp, // timestamp
    val amount: String,
    val credit_account: String, // payto form,
    val debit_account: String,
    val reserve_pub: String
)

data class TalerIncomingHistory(
    var incoming_transactions: MutableList<TalerIncomingBankTransaction> = mutableListOf()
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

fun expectLong(param: String?): Long {
    if (param == null) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'$param' is not Long")
    }
    return try {
        param.toLong()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'$param' is not Long")
    }
}

/** Helper handling 'start' being optional and its dependence on 'delta'.  */
fun handleStartArgument(start: String?, delta: Int): Long {
    if (start == null) {
        if (delta >= 0)
            return -1
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

/**
 * Tries to extract a valid reserve public key from the raw subject line
 */
fun extractReservePubFromSubject(rawSubject: String): String? {
    val re = "\\b[a-z0-9A-Z]{52}\\b".toRegex()
    val result = re.find(rawSubject.replace("[\n]+".toRegex(), "")) ?: return null
    return result.value.uppercase()
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

fun talerFilter(payment: NexusBankTransactionEntity, txDtls: TransactionDetails) {
    var isInvalid = false // True when pub is invalid or duplicate.
    val subject = txDtls.unstructuredRemittanceInformation
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
    val debtorAgent = txDtls.debtorAgent
    if (debtorAgent == null) {
        // FIXME: Report payment, we can't even send it back
        logger.warn("missing debtor agent")
        return
    }
    if (debtorAgent.bic == null) {
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
        // FIXME: send back!
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
            debtorIban, debtorAgent.bic, debtorName
        )
    }
}

fun maybeTalerRefunds(bankAccount: NexusBankAccountEntity, lastSeenId: Long) {
    logger.debug(
        "Searching refundable payments of account: ${bankAccount.bankAccountName}," +
                " after last seen transaction id: $lastSeenId"
    )
    transaction {
        TalerInvalidIncomingPaymentsTable.innerJoin(NexusBankTransactionsTable,
            { NexusBankTransactionsTable.id }, { TalerInvalidIncomingPaymentsTable.payment }).select {
            TalerInvalidIncomingPaymentsTable.refunded eq false and
                    (NexusBankTransactionsTable.bankAccount eq bankAccount.id.value) and
                    (NexusBankTransactionsTable.id greater lastSeenId)

        }.forEach {
            val paymentData = jacksonObjectMapper().readValue(
                it[NexusBankTransactionsTable.transactionJson],
                CamtBankAccountEntry::class.java
            )
            if (paymentData.batches == null) {
                logger.error(
                    "A singleton batched payment was expected to be refunded," +
                            " but none was found (in transaction (AcctSvcrRef): ${paymentData.accountServicerRef})"
                )
                throw NexusError(HttpStatusCode.InternalServerError, "Unexpected void payment, cannot refund")
            }
            val debtorAccount = paymentData.batches[0].batchTransactions[0].details.debtorAccount
            if (debtorAccount?.iban == null) {
                logger.error("Could not find a IBAN to refund in transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "IBAN to refund not found")
            }
            val debtorAgent = paymentData.batches[0].batchTransactions[0].details.debtorAgent
            if (debtorAgent?.bic == null) {
                logger.error("Could not find the BIC of refundable IBAN at transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "BIC to refund not found")
            }
            val debtorPerson = paymentData.batches[0].batchTransactions[0].details.debtor
            if (debtorPerson?.name == null) {
                logger.error("Could not find the owner's name of refundable IBAN at transaction (AcctSvcrRef): ${paymentData.accountServicerRef}, aborting refund")
                throw NexusError(HttpStatusCode.InternalServerError, "Name to refund not found")
            }
            // FIXME: investigate this amount!
            val amount = paymentData.batches[0].batchTransactions[0].amount
            NexusAssert(
                it[NexusBankTransactionsTable.creditDebitIndicator] == "CRDT" &&
                        it[NexusBankTransactionsTable.bankAccount] == bankAccount.id,
                "Cannot refund a _outgoing_ payment!"
            )
            // FIXME: the amount to refund should be reduced, according to the bounce fee
            // see bug #7116.
            addPaymentInitiation(
                Pain001Data(
                    creditorIban = debtorAccount.iban,
                    creditorBic = debtorAgent.bic,
                    creditorName = debtorPerson.name,
                    subject = "Taler refund of: ${paymentData.batches[0].batchTransactions[0].details.unstructuredRemittanceInformation}",
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
                        exchange_base_url = "FIXME-to-request-along-subscriber-registration"
                    )
                )
            }
        }
    }
    call.respond(TextContent(customConverter(history), ContentType.Application.Json))
}

/**
 * Handle a /taler-wire-gateway/history/incoming request.
 */
private suspend fun historyIncoming(call: ApplicationCall) {
    val facadeId = expectNonNull(call.parameters["fcid"])
    call.request.requirePermission(PermissionQuery("facade", facadeId, "facade.talerwiregateway.history"))
    val param = call.expectUrlParameter("delta")
    val delta: Int = try {
        param.toInt()
    } catch (e: Exception) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "'${param}' is not Int")
    }
    val start: Long = handleStartArgument(call.request.queryParameters["start"], delta)
    val history = TalerIncomingHistory()
    val startCmpOp = getComparisonOperator(delta, start, TalerIncomingPaymentsTable)
    transaction {
        val orderedPayments = TalerIncomingPaymentEntity.find {
            startCmpOp
        }.orderTaler(delta)
        if (orderedPayments.isNotEmpty()) {
            orderedPayments.subList(0, min(abs(delta), orderedPayments.size)).forEach {
                history.incoming_transactions.add(
                    TalerIncomingBankTransaction(
                        // Rounded timestamp
                        date = GnunetTimestamp(it.timestampMs / 1000L),
                        row_id = it.id.value,
                        amount = "${it.payment.currency}:${it.payment.amount}",
                        reserve_pub = it.reservePublicKey,
                        credit_account = buildIbanPaytoUri(
                            it.payment.bankAccount.iban,
                            it.payment.bankAccount.bankCode,
                            it.payment.bankAccount.accountHolder,
                        ),
                        debit_account = it.debtorPaytoUri
                    )
                )
            }
        }
    }
    val responseCode = if (history.incoming_transactions.size == 0)
        HttpStatusCode.NoContent else
            HttpStatusCode.OK
    return call.respond(
        status = responseCode,
        TextContent(customConverter(history),
            ContentType.Application.Json)
    )
}

/**
 * This call proxies /admin/add/incoming to the Sandbox,
 * which is the service keeping the transactions ledger.
 * The credentials are ASSUMED to be exchange/x (user/pass).
 *
 * In the future, a dedicate "add-incoming" facade should
 * be provided, offering the mean to store the credentials
 * at configuration time.
 *
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
        val ebicsData = NexusEbicsSubscribersTable.select {
            NexusEbicsSubscribersTable.nexusBankConnection eq conn.id
        }.firstOrNull() ?: throw internalServerError(
            "Connection '${conn.connectionId}' doesn't have EBICS"
        )
        // Resort Sandbox URL from EBICS endpoint.
        val sandboxUrl = URL(ebicsData[NexusEbicsSubscribersTable.ebicsURL])
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
        },
            facadeState.bankAccount
        )
    }
    val client = HttpClient { followRedirects = true }
    try {
        client.post(fromDb.first) {
            setBody(currentBody)
            basicAuth("exchange", "x")
            contentType(ContentType.Application.Json)
        }
    } catch (e: ClientRequestException) {
        logger.error("Proxying /admin/add/incoming to the Sandbox failed: $e")
    } catch (e: Exception) {
        logger.error("Could not proxy /admin/add/incoming to the Sandbox: $e")
    }
    /**
     * At this point, Sandbox booked the payment.  Now the "row_id"
     * value to put in the response needs to be resorted; that may
     * be known by fetching a fresh C52 report, then let Nexus ingest
     * the result, and finally _optimistically_ pick the latest entry
     * in the received payments.  */
    fetchBankAccountTransactions(
        client,
        FetchSpecLatestJson(
            FetchLevel.REPORT,
            null
        ),
        fromDb.second
    )
    /**
     * The latest incoming payment should now be found among
     * the ingested ones.
     */
    val lastIncomingPayment = transaction {
        val lastRecord = TalerIncomingPaymentEntity.all().last()
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
            val version = "0.0.0"
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
