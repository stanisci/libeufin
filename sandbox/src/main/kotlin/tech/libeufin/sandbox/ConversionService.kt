package tech.libeufin.sandbox

import CamtBankAccountEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.util.*
import java.math.BigDecimal
import kotlin.system.exitProcess

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
 *     transactions via its JSON API. => Fixed.
 * 4.  At this time, Nexus does NOT offer any filter when it serves the
 *     transactions via its JSON API. => Can be fixed by using the TWG.
 */

// DEFINITIONS AND HELPERS

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
 * Response format of Nexus GET /transactions.
 */
data class TransactionItem(
    val index: String,
    val camtData: CamtBankAccountEntry
)
data class NexusTransactions(
    val transactions: List<TransactionItem>
)

/**
 * This exception signals that the buy-in service could NOT
 * GET the list of fiat transactions from Nexus due to a client
 * error.  Because this is fatal (e.g. wrong credentials, URL not found..),
 * the service should be stopped.
 */
class BuyinClientError : Exception()

/**
 * This exception signals that POSTing a cash-out operation
 * to Nexus failed due to the client.  This is a fatal condition
 * therefore the monitor should be stopped.
 */
class CashoutClientError : Exception()
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
            catch (e: BuyinClientError) {
                logger.error("The buy-in monitor had a client error while GETting new" +
                        " transactions from Neuxs.  Stopping it")
                // Rethrowing and let the caller manage it
                throw e
            }
            // Tolerating any other error type that's not due to the client.
            catch (e: Exception) {
                logger.error("Sandbox fiat-incoming monitor excepted: ${e.message}")
            }
            delay(newIterationTimeout)
        }
    }
}

// BUY-IN SIDE.

/**
 * Applies the buy-in ratio and fees to the fiat amount
 * that came from Nexus.  The result is the regional amount
 * that will be wired to the exchange Sandbox account.
 */
private fun applyBuyinRatioAndFees(
    amount: BigDecimal,
    ratiosAndFees: RatioAndFees
): BigDecimal =
    ((amount * ratiosAndFees.buy_at_ratio.toBigDecimal())
            - ratiosAndFees.buy_in_fee.toBigDecimal()).roundToTwoDigits()

private fun ensureDisabledRedirects(client: HttpClient) {
    client.config {
        if (followRedirects) throw Exception(
            "HTTP client follows redirects, please disable."
        )
    }
}
/**
 * This function downloads the incoming fiat transactions from Nexus,
 * stores them into the database and triggers the related wire transfer
 * to the Taler exchange (to be specified in 'accountToCredit').  Once
 * started, this function is not supposed to return, except on _client
 * side_ errors.  On server side errors it pauses and retries.  When
 * it returns, the caller is expected to handle the error.
 */
fun buyinMonitor(
    demobankName: String, // used to get config values.
    client: HttpClient,
    accountToCredit: String,
    accountToDebit: String = "admin"
) {
    ensureDisabledRedirects(client)
    val demobank = ensureDemobank(demobankName)
    /**
     * Getting the config values to send authenticated requests
     * to Nexus.  Sandbox needs one account at Nexus before being
     * able to use these values.
     */
    val nexusBaseUrl = getConfigValueOrThrow(demobank.config::nexusBaseUrl)
    val usernameAtNexus = getConfigValueOrThrow(demobank.config::usernameAtNexus)
    val passwordAtNexus = getConfigValueOrThrow(demobank.config::passwordAtNexus)
    /**
     * This is the endpoint where Nexus serves all the transactions that
     * have ingested from the fiat bank.
     */
    val endpoint = "bank-accounts/$usernameAtNexus/transactions"
    val uriWithoutStart = joinUrl(nexusBaseUrl, endpoint) + "?long_poll_ms=$waitTimeout"

    // downloadLoop does already try-catch (without failing the process).
    downloadLoop {
        /**
         * This bank account will act as the debtor, once a new fiat
         * payment is detected.  It's the debtor that pays the related
         * regional amount to the exchange, in order to start a withdrawal
         * operation (in regional coins).
         */
        val debitBankAccount = getBankAccountFromLabel(accountToDebit)
        /**
         * Setting the 'start' URI param in the following command
         * lets Sandbox receive only unseen payments from Nexus.
         */
        val uriWithStart = "$uriWithoutStart&start=${debitBankAccount.lastFiatFetch}"
        runBlocking {
            // Maybe get new fiat transactions.
            logger.debug("GETting fiat transactions from: $uriWithStart")
            val resp = client.get(uriWithStart) {
                expectSuccess = false // Avoids excepting on !2xx
                basicAuth(usernameAtNexus, passwordAtNexus)
            }
            // The server failed, pause and try again
            if (resp.status.value.toString().startsWith('5')) {
                logger.error("Buy-in monitor requested to a failing Nexus.  Retry.")
                logger.error("Nexus responded: ${resp.bodyAsText()}")
                return@runBlocking
            }
            // The client failed, fail the process.
            if (resp.status.value.toString().startsWith('4')) {
                logger.error("Buy-in monitor failed at GETting to Nexus.  Stopping the buy-in monitor.")
                logger.error("Nexus responded: ${resp.bodyAsText()}")
                throw BuyinClientError()
            }
            // Expect 200 OK.  What if 3xx?
            if (resp.status.value != HttpStatusCode.OK.value) {
                logger.error("Unhandled response status ${resp.status.value}, failing Sandbox")
                throw BuyinClientError()
            }
            // Nexus responded 200 OK, analyzing the result.
            /**
             * Wire to "admin" if the subject is a public key, or do
             * nothing otherwise.
             */
            val respObj = jacksonObjectMapper().readValue(
                resp.bodyAsText(),
                NexusTransactions::class.java
            ) // errors are logged by the caller (without failing).
            respObj.transactions.forEach {
                // Ignoring payments with an invalid reserved public key.
                if (extractReservePubFromSubject(it.camtData.getSingletonSubject()) == null)
                    return@forEach
                // Extracts the amount and checks it's at most two fractional digits.
                val maybeValidAmount = it.camtData.amount.value
                if (!validatePlainAmount(maybeValidAmount)) {
                    logger.error("Nexus gave one amount with invalid fractional digits: $maybeValidAmount." +
                            "  The transaction has index ${it.index}")
                    // Advancing the last fetched pointer, to avoid GETting
                    // this invalid payment again.
                    transaction {
                        debitBankAccount.refresh()
                        debitBankAccount.lastFiatFetch = it.index
                    }
                }
                val convertedAmount = applyBuyinRatioAndFees(
                    maybeValidAmount.toBigDecimal(),
                    ratiosAndFees
                )
                transaction {
                    wireTransfer(
                        debitAccount = accountToDebit,
                        creditAccount = accountToCredit,
                        demobank = demobankName,
                        subject = it.camtData.getSingletonSubject(),
                        amount = "${demobank.config.currency}:$convertedAmount"
                    )
                    // Nexus enqueues the transactions such that the index increases.
                    // If Sandbox crashes here, it'll ask again using the last successful
                    // index as the start parameter.  Being this an exclusive bound, only
                    // transactions later than it are expected.
                    debitBankAccount.refresh()
                    debitBankAccount.lastFiatFetch = it.index
                }
            }
        }
    }
}

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

// CASH-OUT SIDE.

/**
 * This function listens for regio-incoming events (LIBEUFIN_REGIO_TX)
 * on the 'watchedBankAccount' and submits the related cash-out payment
 * to Nexus.  The fiat payment will then take place ENTIRELY on Nexus'
 * responsibility.
 */
suspend fun cashoutMonitor(
    httpClient: HttpClient,
    watchedBankAccount: String = "admin",
    demobankName: String = "default", // used to get config values.
    dbEventTimeout: Long = 0 // 0 waits forever.
) {
    ensureDisabledRedirects(httpClient)
    // Register for a REGIO_TX event.
    val eventChannel = buildChannelName(
        NotificationsChannelDomains.LIBEUFIN_REGIO_TX,
        watchedBankAccount
    )
    val objectMapper = jacksonObjectMapper()
    val demobank = getDemobank(demobankName)
    val bankAccount = getBankAccountFromLabel(watchedBankAccount)
    val config = demobank?.config ?: throw internalServerError(
        "Demobank '$demobankName' has no configuration."
    )
    /**
     * The monitor needs the cash-out currency to correctly POST
     * payment initiations at Nexus.  Recall: Nexus bank accounts
     * do not mandate any particular currency, as they serve as mere
     * bridges to the backing bank.  And: a backing bank may have
     * multiple currencies, or the backing bank may not explicitly
     * specify any currencies to be _the_ currency of the backed
     * bank account.
     */
    if (config.cashoutCurrency == null) {
        logger.error("Config lacks cash-out currency.")
        exitProcess(1)
    }
    val nexusBaseUrl = getConfigValueOrThrow(config::nexusBaseUrl)
    val usernameAtNexus = getConfigValueOrThrow(config::usernameAtNexus)
    val passwordAtNexus = getConfigValueOrThrow(config::passwordAtNexus)
    val paymentInitEndpoint = nexusBaseUrl.run {
        var nexusBaseUrlFromConfig = this
        if (!nexusBaseUrlFromConfig.endsWith('/'))
            nexusBaseUrlFromConfig += '/'
        /**
         * WARNING: Nexus gives the possibility to have bank account names
         * DIFFERENT from their owner's username.  Sandbox however MUST have
         * its Nexus bank account named THE SAME as its username.
         */
        nexusBaseUrlFromConfig + "bank-accounts/$usernameAtNexus/payment-initiations"
    }
    while (true) {
        val listenHandle = PostgresListenHandle(eventChannel)
        // pessimistically LISTEN
        listenHandle.postgresListen()
        // but optimistically check for data, case some
        // arrived _before_ the LISTEN.
        var newTxs = getUnsubmittedTransactions(watchedBankAccount)
        // Data found, UNLISTEN.
        if (newTxs.isNotEmpty()) {
            logger.debug("Found cash-out's without waiting any DB event.")
            listenHandle.postgresUnlisten()
        }
        // Data not found, wait.
        else {
            logger.debug("Need to wait a DB event for new cash-out's")
            val isNotificationArrived = listenHandle.waitOnIODispatchers(dbEventTimeout)
            if (isNotificationArrived && listenHandle.receivedPayload == "CRDT")
                newTxs = getUnsubmittedTransactions(watchedBankAccount)
        }
        if (newTxs.isEmpty()) {
            logger.debug("DB event timeout expired")
            continue
        }
        logger.debug("POSTing new cash-out's")
        newTxs.forEach {
            logger.debug("POSTing cash-out '${it.subject}' to $paymentInitEndpoint")
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
                val bic = it.creditorBic
                val amount = "${config.cashoutCurrency}:${it.amount}"
                val subject = it.subject
                val name = it.creditorName
            }
            val resp = try {
                httpClient.post(paymentInitEndpoint) {
                    expectSuccess = false // Avoids excepting on !2xx
                    basicAuth(usernameAtNexus, passwordAtNexus)
                    contentType(ContentType.Application.Json)
                    setBody(objectMapper.writeValueAsString(body))
                }
            }
            // Hard-error, response did not even arrive.
            catch (e: Exception) {
                logger.error("Cash-out monitor could not reach Nexus.  Pause and retry")
                logger.error(e.message)
                /**
                 * Explicit delaying because the monitor normally
                 * waits on DB events, and this retry likely won't
                 * wait on a DB event.
                 */
                delay(2000)
                return@forEach
            }
            // Server fault.  Pause and retry.
            if (resp.status.value.toString().startsWith('5')) {
                logger.error("Cash-out monitor POSTed to a failing Nexus.  Pause and retry")
                logger.error("Server responded: ${resp.bodyAsText()}")
                /**
                 * Explicit delaying because the monitor normally
                 * waits on DB events, and this retry likely won't
                 * wait on a DB event.
                 */
                delay(2000L)
                return@forEach
            }
            // Client fault, fail Sandbox.
            if (resp.status.value.toString().startsWith('4')) {
                logger.error("Cash-out monitor failed at POSTing to Nexus.")
                logger.error("Nexus responded: ${resp.bodyAsText()}")
                throw CashoutClientError()
            }
            // Expecting 200 OK.  What if 3xx?
            if (resp.status.value != HttpStatusCode.OK.value) {
                logger.error("Cash-out monitor, unhandled response status: ${resp.status.value}.")
                throw CashoutClientError()
            }
            // Successful case, mark the wire transfer as submitted,
            // and advance the pointer to the last submitted payment.
            val responseBody = resp.bodyAsText()
            transaction {
                CashoutSubmissionEntity.new {
                    localTransaction = it.id
                    submissionTime = resp.responseTime.timestamp
                    /**
                     * The following block associates the submitted payment
                     * to the UID that Nexus assigned to it.  It is currently not
                     * used in Sandbox, but might help for reconciliation.
                     */
                    if (responseBody.isNotEmpty())
                        maybeNexusResposnse = responseBody
                }
                // Advancing the 'last submitted bookmark', to avoid
                // handling the same transaction multiple times.
                bankAccount.lastFiatSubmission = it
            }
        }
    }
}
