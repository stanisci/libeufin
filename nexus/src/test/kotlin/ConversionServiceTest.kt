import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.sandbox.*

class ConversionServiceTest {
    /**
     * Testing the buy-in monitor in the normal case: Nexus
     * communicates a new incoming fiat transaction and the
     * monitor wires funds to the exchange.
     */
    @Test
    fun buyinTest() {
        // First create an incoming fiat payment _at Nexus_.
        // This payment is addressed to the Nexus user whose
        // (Nexus) credentials will be used by Sandbox to fetch
        // new incoming fiat payments.
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
                application(nexusApp)
                // Start the buy-in monitor to let it download the fiat transaction.
                runBlocking {
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
                                client = client
                            )
                        }
                    }
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
                assert(BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.creditorIban eq "AT561936082973364859" and (
                            BankAccountTransactionsTable.direction eq "CRDT"
                    )
                }.count() == 1L)

                // Asserting that the one incoming transactino has the wired reserve public key.
                assert(BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.creditorIban eq "AT561936082973364859"
                }.first().subject == reservePub)
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
            prepSandboxDb()
            prepNexusDb()
            wireTransfer(
                debitAccount = "foo",
                creditAccount = "admin",
                subject = "fiat #0",
                amount = "TESTKUDOS:3"
            )
            testApplication {
                application(nexusApp)
                runBlocking {
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
                            runBlocking { cashoutMonitor(client) }
                        }
                    }
                    delay(1000L) // Lets DB persist the information.
                    job.cancelAndJoin()
                }
            }
            transaction {
                assert(CashoutSubmissionEntity.all().count() == 1L)
                assert(CashoutSubmissionEntity.all().first().isSubmitted)
            }
        }
    }

    /**
     * Tests whether the conversion service is able to skip
     * submissions that had problems and proceed to new ones.
    ----------------------------------------------------------
     * Ignoring the test because the new version just fails the
     * process on client side errors.  Still however keeping the
     * (ignored) test as a model to create faulty situations.
     */
    @Ignore
    @Test
    fun testWrongSubmissionSkip() {
        withTestDatabase {
            prepSandboxDb(); prepNexusDb()
            val engine400 = MockEngine { respondBadRequest() }
            val mockedClient = HttpClient(engine400)
            runBlocking {
                val monitorJob = async(Dispatchers.IO) { cashoutMonitor(mockedClient) }
                launch {
                    wireTransfer(
                        debitAccount = "foo",
                        creditAccount = "admin",
                        subject = "fiat",
                        amount = "TESTKUDOS:3"
                    )
                    // Give enough time to let a flawed monitor submit the request twice.
                    delay(6000)
                    transaction {
                        // The request was submitted only once.
                        assert(CashoutSubmissionEntity.all().count() == 1L)
                        // The monitor marked it as failed.
                        assert(CashoutSubmissionEntity.all().first().hasErrors)
                        // The submission pointer got advanced by one.
                        assert(getBankAccountFromLabel("admin").lastFiatSubmission?.id?.value == 1L)
                    }
                    monitorJob.cancel()
                }
            }
        }
    }
}