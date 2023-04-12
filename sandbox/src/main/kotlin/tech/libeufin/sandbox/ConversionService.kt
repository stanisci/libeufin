package tech.libeufin.sandbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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

// Temporarily hard-coded.  According to fiat times, these values could be WAY higher.
val longPollMs = 30000L // 30s long-polling.
val loopNewReqMs = 2000L // 2s for the next request.

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
            delay(loopNewReqMs)
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

/**
 * This function listens for regio-incoming events (LIBEUFIN_REGIO_INCOMING)
 * and submits the related cash-out payment to Nexus.  The fiat payment will
 * then take place ENTIRELY on Nexus' responsibility.
 */
// issueCashout()
