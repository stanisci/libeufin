import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.sandbox.*

class ConversionServiceTest {
    /**
     * Tests whether the conversion service is able to skip
     * submissions that had problems and proceed to new ones.
     */
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

    /**
     * Checks that the cash-out monitor reacts after
     * a CRDT transaction arrives at the designated account.
     */
    @Test
    fun cashoutTest() {
        withTestDatabase {
            prepSandboxDb(); prepNexusDb()
            wireTransfer(
                debitAccount = "foo",
                creditAccount = "admin",
                subject = "fiat",
                amount = "TESTKUDOS:3"
            )
            testApplication {
                application(nexusApp)
                runBlocking {
                    val monitorJob = launch(Dispatchers.IO) { cashoutMonitor(client) }
                    launch {
                        delay(4000L)
                        transaction {
                            assert(CashoutSubmissionEntity.all().count() == 1L)
                            assert(CashoutSubmissionEntity.all().first().isSubmitted)
                        }
                        monitorJob.cancel()
                    }
                }
            }
        }
    }
}