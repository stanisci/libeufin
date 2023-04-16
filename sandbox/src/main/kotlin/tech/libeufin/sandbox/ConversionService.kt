package tech.libeufin.sandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*

/**
 * This file contains the logic for downloading/submitting incoming/outgoing
 * fiat transactions to Nexus.  It needs the following values for operating.
 *
 * 1.  Nexus URL.
 * 2.  Credentials to authenticate at Nexus JSON API.
 * 3.  Long-polling interval.
 * 4.  Frequency of the download loop.
 *
 * Notes:
 *
 * 1.  The account to credit on incoming transactions is ALWAYS "admin".
 * 2.  The time to submit a new payment is as soon as "admin" receives one
 *     incoming regional payment.
 * 3.  At this time, Nexus does NOT offer long polling when it serves the
 *     transactions via its JSON API.
 * 4.  At this time, Nexus does NOT offer any filter when it serves the
 *     transactions via its JSON API.
 */

/**
 * Timeout the HTTP client waits for the server to respond,
 * after the request is made.
 */
val waitTimeout = 30000L

/**
 * Time to wait before HTTP requesting again to the server.
 * This helps to avoid tight cycles in case the server responds
 * quickly or the client doesn't long-poll.
 */
val newIterationTimeout = 2000L

/**
 * Executes the 'block' function every 'loopNewReqMs' milliseconds.
 * Does not exit/fail the process upon exceptions - just logs them.
 */
fun downloadLoop(block: () -> Unit) {
    // Needs "runBlocking {}" to call "delay()" and in case 'block'
    // contains suspend functions.
    runBlocking {
        while(true) {
            try { block() }
            catch (e: Exception) {
                /**
                 * Not exiting to tolerate network issues, or optimistically
                 * tolerate problems not caused by Sandbox itself.
                 */
                logger.error("Sandbox fiat-incoming monitor excepted: ${e.message}")
            }
            delay(newIterationTimeout)
        }
    }
}

/**
 * This function downloads the incoming fiat transactions from Nexus,
 * stores them into the database and signals their arrival (LIBEUFIN_FIAT_INCOMING)
 * to allow crediting the "admin" account.
 */
// fetchTransactions()

/**
 * This function listens for fiat-incoming events (LIBEUFIN_FIAT_INCOMING)
 * and credits the "admin" account as a reaction.  Lastly, the Nexus instance
 * wired to Sandbox will pick the new payment and serve it via its TWG, but
 * this is OUT of the Sandbox scope.
 */
// creditAdmin()

// DB query helper.  The List return type (instead of SizedIterable) lets
// the caller NOT open a transaction block to access the values -- although
// some operations _on the values_ may be forbidden.
private fun getUnsubmittedTransactions(bankAccountLabel: String): List<BankAccountTransactionEntity> {
    return transaction {
        val bankAccount = getBankAccountFromLabel(bankAccountLabel)
        val lowerExclusiveLimit = bankAccount.lastFiatSubmission?.id?.value ?: 0
        BankAccountTransactionEntity.find {
            BankAccountTransactionsTable.id greater lowerExclusiveLimit and (
                BankAccountTransactionsTable.direction eq "CRDT"
            )
        }.sortedBy { it.id }.map { it }
        // The latest payment must occupy the highest index,
        // to reliably update the bank account row with the last
        // submitted cash-out.
    }
}

/**
 * This function listens for regio-incoming events (LIBEUFIN_REGIO_TX)
 * and submits the related cash-out payment to Nexus.  The fiat payment will
 * then take place ENTIRELY on Nexus' responsibility.
 */
suspend fun cashoutMonitor(
    httpClient: HttpClient,
    bankAccountLabel: String = "admin",
    demobankName: String = "default" // used to get config values.
) {
    // Register for a REGIO_TX event.
    val eventChannel = buildChannelName(
        NotificationsChannelDomains.LIBEUFIN_REGIO_TX,
        bankAccountLabel
    )
    val objectMapper = jacksonObjectMapper()
    val demobank = getDemobank(demobankName)
    val bankAccount = getBankAccountFromLabel(bankAccountLabel)
    val config = demobank?.config ?: throw internalServerError(
        "Demobank '$demobankName' has no configuration."
    )
    val nexusBaseUrl = getConfigValueOrThrow(config::nexusBaseUrl)
    val usernameAtNexus = getConfigValueOrThrow(config::usernameAtNexus)
    val passwordAtNexus = getConfigValueOrThrow(config::passwordAtNexus)
    val paymentInitEndpoint = nexusBaseUrl.run {
        var ret = this
        if (!ret.endsWith('/'))
            ret += '/'
        /**
         * WARNING: Nexus gives the possibility to have bank account names
         * DIFFERENT from their owner's username.  Sandbox however MUST have
         * its Nexus bank account named THE SAME as its username (until the
         * config will allow to change).
         */
        ret + "bank-accounts/$usernameAtNexus/payment-initiations"
    }
    while (true) {
        // delaying here avoids to delay in multiple places (errors,
        // lack of action, success)
        delay(2000)
        val listenHandle = PostgresListenHandle(eventChannel)
        // pessimistically LISTEN
        listenHandle.postgresListen()
        // but optimistically check for data, case some
        // arrived _before_ the LISTEN.
        var newTxs = getUnsubmittedTransactions(bankAccountLabel)
        // Data found, UNLISTEN.
        if (newTxs.isNotEmpty())
            listenHandle.postgresUnlisten()
        // Data not found, wait.
        else {
            // OK to block, because the next event is going to
            // be _this_ one.  The caller should however execute
            // this whole logic in a thread other than the main
            // HTTP server.
            val isNotificationArrived = listenHandle.postgresGetNotifications(waitTimeout)
            if (isNotificationArrived && listenHandle.receivedPayload == "CRDT")
                newTxs = getUnsubmittedTransactions(bankAccountLabel)
        }
        if (newTxs.isEmpty())
            continue
        newTxs.forEach {
            val body = object {
                /**
                 * This field is UID of the request _as assigned by the
                 * client_.  That helps to reconcile transactions or lets
                 * Nexus implement idempotency.  It will NOT identify the created
                 * resource at the server side.  The ID of the created resource is
                 * assigned _by Nexus_ and communicated in the (successful) response.
                 */
                val uid = it.accountServicerReference
                val iban = it.creditorIban
                val bic = it.debtorBic
                val amount = "${it.currency}:${it.amount}"
                val subject = it.subject
                val name = it.creditorName
            }
            val resp = try {
                httpClient.post(paymentInitEndpoint) {
                    expectSuccess = false // Avoid excepting on !2xx
                    basicAuth(usernameAtNexus, passwordAtNexus)
                    contentType(ContentType.Application.Json)
                    setBody(objectMapper.writeValueAsString(body))
                }
            }
            // Hard-error, response did not even arrive.
            catch (e: Exception) {
                logger.error(e.message)
                // mark as failed and proceed to the next one.
                transaction {
                    CashoutSubmissionEntity.new {
                        this.localTransaction = it.id
                        this.hasErrors = true
                    }
                    bankAccount.lastFiatSubmission = it
                }
                return@forEach
            }
            // Handle the non 2xx error case.  Here we try
            // to store the response from Nexus.
            if (resp.status.value != HttpStatusCode.OK.value) {
                val maybeResponseBody = resp.bodyAsText()
                logger.error(
                    "Fiat submission response was: $maybeResponseBody," +
                            " status: ${resp.status.value}"
                )
                transaction {
                    CashoutSubmissionEntity.new {
                        localTransaction = it.id
                        this.hasErrors = true
                        if (maybeResponseBody.length > 0)
                            this.maybeNexusResposnse = maybeResponseBody
                    }
                    bankAccount.lastFiatSubmission = it
                }
                return@forEach
            }
            // Successful case, mark the wire transfer as submitted,
            // and advance the pointer to the last submitted payment.
            val responseBody = resp.bodyAsText()
            transaction {
                CashoutSubmissionEntity.new {
                    localTransaction = it.id
                    hasErrors = false
                    submissionTime = resp.responseTime.timestamp
                    isSubmitted = true
                    // Expectedly is > 0 and contains the submission
                    // unique identifier _as assigned by Nexus_.  Not
                    // currently used by Sandbox, but may help to resolve
                    // disputes.
                    if (responseBody.length > 0)
                        maybeNexusResposnse = responseBody
                }
                // Advancing the 'last submitted bookmark', to avoid
                // handling the same transaction multiple times.
                bankAccount.lastFiatSubmission = it
            }
        }
    }
}
