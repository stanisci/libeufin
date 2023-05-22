import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.sandbox.*
import tech.libeufin.util.parseAmount

class ConversionServiceTest {
    private fun CoroutineScope.launchBuyinMonitor(httpClient: HttpClient): Job {
        val job = launch {
            /**
             * The runInterruptible wrapper lets code without suspension
             * points be cancel()'d.  Without it, such code would ignore
             * any call to cancel() and the test never return.
             */
            runInterruptible {
                buyinMonitor(
                    demobankName = "default",
                    accountToCredit = "exchange-0",
                    client = httpClient
                )
            }
        }
        return job
    }
    /**
     * Testing the buy-in monitor in all the HTTP scenarios,
     * successful case, client's and server's error cases.
     */
    @Test
    fun buyinTest() {
        // 1, testing the successful case.
        /* First create an incoming fiat payment _at Nexus_.
          This payment is addressed to the Nexus user whose
          (Nexus) credentials will be used by Sandbox to fetch
          new incoming fiat payments. */
        withTestDatabase {
            prepSandboxDb(currency = "REGIO")
            prepNexusDb()
            // Credits 22 TESTKUDOS to "foo".  This information comes
            // normally from the fiat bank that Nexus is connected to.
            val reservePub = "GX5H5RME193FDRCM1HZKERXXQ2K21KH7788CKQM8X6MYKYRBP8F0"
            newNexusBankTransaction(
                currency = "TESTKUDOS",
                value = "22",
                /**
                 * If the subject does NOT have the format of a public key,
                 * the conversion service does NOT wire any regio amount to the
                 * exchange, just ignores it.
                 */
                subject = reservePub
            )
            // Start Nexus, to let it serve the fiat transaction.
            testApplication {
                val client = this.createClient {
                    followRedirects = false
                }
                application(nexusApp)
                // Start the buy-in monitor to let it download the fiat transaction.
                runBlocking {
                    val job = launchBuyinMonitor(client)
                    delay(1000L) // Lets the DB persist.
                    job.cancelAndJoin()
                }
            }
            // Checking that exchange got the converted amount.
            transaction {
                /**
                 * Asserting that the exchange has only one incoming transaction.
                 *
                 * The Sandbox DB has two entries where the exchange IBAN shows
                 * as the 'creditorIban': one DBIT related to the "admin" account,
                 * and one CRDT related to the "exchange-0" account.  Thus filtering
                 * the direction is also required.
                 */
                assert(
                    BankAccountTransactionEntity.find {
                        BankAccountTransactionsTable.creditorIban eq "AT561936082973364859" and (
                            BankAccountTransactionsTable.direction eq "CRDT"
                        )
                    }.count() == 1L
                )
                val boughtIn = BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.creditorIban eq "AT561936082973364859"
                }.first()
                // Asserting that the one incoming transaction has the wired reserve public key
                // and the regional currency.
                assert(boughtIn.subject == reservePub && boughtIn.currency == "REGIO")
            }
            // 2, testing the client side error case.
            assertException<BuyinClientError>(
                {
                    runBlocking {
                        /**
                         * As soon as the buy-in monitor requests again the history
                         * to Nexus, it'll get 400 from the mock client.
                         */
                        launchBuyinMonitor(getMockedClient { respondBadRequest() })
                    }
                }
            )
            /**
             * 3, testing the server side error case.  Here the monitor should
             * NOT throw any error and instead keep operating normally.  This allows
             * Sandbox to tolerate server errors and retry the requests.
             */
            runBlocking {
                /**
                 * As soon as the buy-in monitor requests again the history
                 * to Nexus, it'll get 500 from the mock client.
                 */
                val job = launchBuyinMonitor(getMockedClient { respondError(HttpStatusCode.InternalServerError) })
                delay(1000L)
                // Getting here means no exceptions.  Can now cancel the service.
                job.cancelAndJoin()
            }
            /**
             * 4, testing the unhandled error case.  This case is treated
             * as a client error, to signal the calling logic to intervene.
             */
            assertException<BuyinClientError>(
                {
                    runBlocking {
                        /**
                         * As soon as the buy-in monitor requests again the history
                         * to Nexus, it'll get 307 from the mock client.
                         */
                        launchBuyinMonitor(getMockedClient { respondRedirect() })
                    }
                }
            )
        }
    }
    private fun CoroutineScope.launchCashoutMonitor(httpClient: HttpClient): Job {
        val job = launch {
            /**
             * The runInterruptible wrapper lets code without suspension
             * points be cancel()'d.  Without it, such code would ignore
             * any call to cancel() and the test never return.
             */
            runInterruptible {
                /**
                 * Without the runBlocking wrapper, cashoutMonitor doesn't
                 * compile.  That's because it is a 'suspend' function and
                 * it needs a coroutine environment to execute; runInterruptible
                 * does NOT provide one.  Furthermore, replacing runBlocking
                 * with "launch {}" would nullify runInterruptible, due to other
                 * jobs that cashoutMonitor internally launches and would escape
                 * the interruptible policy.
                 */
                runBlocking { cashoutMonitor(httpClient) }
            }
        }
        return job
    }

    // This function mocks a 500 response to a cash-out request.
    private fun MockRequestHandleScope.mock500Response(): HttpResponseData {
        return respondError(HttpStatusCode.InternalServerError)
    }
    // This function implements a mock server that checks the currency in the cash-out request.
    private suspend fun MockRequestHandleScope.inspectCashoutCurrency(request: HttpRequestData): HttpResponseData {
        // Asserting that the currency is indeed the FIAT.
        return if (request.url.encodedPath == "/bank-accounts/foo/payment-initiations" && request.method == HttpMethod.Post) {
            val body = jacksonObjectMapper().readTree(request.body.toByteArray())
            val postedAmount = body.get("amount").asText()
            assert(parseAmount(postedAmount).currency == "FIAT")
            respondOk("cash-out-nonce")
        } else {
            println("Cash-out monitor wrongly requested to: ${request.url}")
            // This is a minimal Web server that support only the above endpoint.
            respondError(status = HttpStatusCode.NotImplemented)
        }
    }

    // Abstracts the mock handler installation.
    private fun getMockedClient(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        return HttpClient(MockEngine) {
            followRedirects = false
            engine {
                addHandler {
                        request -> handler(request)
                }
            }
        }
    }
    /**
     * Checks that the cash-out monitor reacts after
     * a CRDT transaction arrives at the designated account.
     */
    @Test
    fun cashoutTest() {
        withTestDatabase {
            prepSandboxDb(
                currency = "REGIO",
                cashoutCurrency = "FIAT"
            )
            prepNexusDb()
            testApplication {
                val client = this.createClient {
                    followRedirects = false
                }
                application(nexusApp)
                // Mock server to intercept and inspect the cash-out request.
                val checkCurrencyClient = HttpClient(MockEngine) {
                    followRedirects = false
                    engine {
                        addHandler { 
                            request -> inspectCashoutCurrency(request)
                        }
                    }
                }
                // Starting the cash-out monitor with the mocked client.
                runBlocking {
                    var job = launchCashoutMonitor(checkCurrencyClient)
                    // Following are various cases of a cash-out scenario.

                    /**
                     * 1, Ordinary/successful case.  We test that the conversion
                     * service sent indeed one request to Nexus and that the currency
                     * is correct.
                     */
                    wireTransfer(
                        debitAccount = "foo",
                        creditAccount = "admin",
                        subject = "fiat #0",
                        amount = "REGIO:3"
                    )
                    delay(1000L) // Lets DB persist the information.
                    // Checking now the Sandbox side, and namely that one
                    // cash-out operation got carried out.
                    transaction {
                        assert(CashoutSubmissionEntity.all().count() == 1L)
                        val op = CashoutSubmissionEntity.all().first()
                        /**
                         * The next assert witnesses that the mock client's
                         * currency assert succeeded.
                         */
                        assert(op.maybeNexusResposnse == "cash-out-nonce")
                    }
                    /* 2, Internal server error case.  We test that after requesting
                     * to a failing Nexus, the last accounted cash-out did NOT increase.
                     */
                    job.cancelAndJoin()
                    val error500Client = HttpClient(MockEngine) {
                        followRedirects = false
                        engine {
                            addHandler {
                                    request -> mock500Response()
                            }
                        }
                    }
                    job = launchCashoutMonitor(error500Client)
                    // Sending a new payment to trigger the conversion service.
                    wireTransfer(
                        debitAccount = "foo",
                        creditAccount = "admin",
                        subject = "fiat #1",
                        amount = "REGIO:2"
                    )
                    delay(1000L) // Lets the reaction complete.
                    job.cancelAndJoin()
                    transaction {
                        val bankaccount = getBankAccountFromLabel("admin")
                        // Checks that the counter did NOT increase.
                        assert(bankaccount.lastFiatSubmission?.id?.value == 1L)
                    }
                    /* Removing now the mocked 500 response and checking that
                     * the problematic cash-out get then sent.  */
                    job = launchCashoutMonitor(client) // Should find the non cashed-out wire transfer and react.
                    delay(1000L) // Lets the reaction complete.
                    job.cancelAndJoin()
                    transaction {
                        val bankaccount = getBankAccountFromLabel("admin")
                        // Checks that the once failing cash-out did go through.
                        assert(bankaccount.lastFiatSubmission?.subject == "fiat #1")
                    }
                    /**
                     * 3, testing the client error case, where
                     * the conversion service is supposed to throw exception.
                     */
                    assertException<CashoutClientError>({
                        runBlocking {
                            launchCashoutMonitor(
                                httpClient = getMockedClient {
                                    tech.libeufin.sandbox.logger.debug("MOCK 400")
                                    /**
                                     * This causes the cash-out request sent to Nexus to
                                     * respond with 400.
                                     */
                                    respondBadRequest()
                                }
                            )
                            // Triggering now a cash-out operation via a new wire transfer to admin.
                            wireTransfer(
                                debitAccount = "foo",
                                creditAccount = "admin",
                                subject = "fiat #2",
                                amount = "REGIO:22"
                            )
                        }})
                    /**
                     * 4, checking a redirect response.  Because this is an unhandled
                     * error case, it is treated as a client error.  No need to wire a
                     * new cash-out to trigger a cash-out request, since the last failed
                     * one will be retried.
                     */
                    assertException<CashoutClientError>({
                        runBlocking {
                            launchCashoutMonitor(
                                getMockedClient {
                                    /**
                                     * This causes the cash-out request sent to Nexus to
                                     * respond with 307 Temporary Redirect.
                                     */
                                    respondRedirect()
                                }
                            )
                        }
                    })
                    /* 5, Mocking a network error.  The previous failed cash-out
                       will again trigger the service to POST to Nexus.  Here the
                       monitor tolerates the failure, as it's not due to its state
                       and should be temporary.
                     */
                    var requestMade = false
                    job = launchCashoutMonitor(
                        getMockedClient {
                            requestMade = true
                            throw Exception("Network Issue.")
                        }
                    )
                    delay(2000L) // Lets the reaction complete.
                    // asserting that the service is still running after the failed request.
                    assert(requestMade && job.isActive)
                    job.cancelAndJoin()
                }
            }
        }
    }
}