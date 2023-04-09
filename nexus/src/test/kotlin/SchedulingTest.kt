import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.nexus.whileTrueOperationScheduler
import tech.libeufin.sandbox.sandboxApp
import java.util.*
import kotlin.concurrent.schedule
import kotlin.text.get

/**
 * This test suite helps to _measure_ the scheduler performance.
 * It is NOT meant to assert on values, but rather to _launch_ and
 * give the chance to monitor the CPU usage with TOP(1)
 */

// This class focuses on the perf. of Nexus scheduling.
class SchedulingTest {
    // Launching the scheduler to measure its perf with TOP(1)
    @Ignore // Ignoring because no assert takes place.
    @Test
    fun normalOperation() {
        withTestDatabase {
            prepNexusDb()
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                whileTrueOperationScheduler(client)
                // javaTimerOperationScheduler(client)
            }
        }
        runBlocking {
            launch { awaitCancellation() }
        }
    }

    // Allows TOP(1) on the bare connection operations without the scheduling overhead.
    // Not strictly related to scheduling, but perf. is a major part of scheduling.
    @Test
    @Ignore // Ignoring because no assert takes place.
    fun bareOperationXLibeufinBank() {
        withTestDatabase {
            prepNexusDb()
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                runBlocking {
                    while (true) {
                        // Even x-libeufin-bank takes 10-20% CPU
                        fetchBankAccountTransactions(
                            client,
                            fetchSpec = FetchSpecAllJson(
                                level = FetchLevel.STATEMENT,
                                bankConnection = "bar"
                            ),
                            accountId = "bar"
                        )
                        delay(1000L)
                    }
                }
            }
        }
    }
    // Same as the previous, but on a EBICS connection.
    // Perf. is only slightly worse than the JSON based x-libeufin-bank connection.
    @Ignore // Ignoring because no assert takes place.
    @Test
    fun bareOperationEbics() {
        withTestDatabase {
            prepNexusDb()
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                runBlocking {
                    while (true) {
                        fetchBankAccountTransactions(
                            client,
                            fetchSpec = FetchSpecAllJson(
                                level = FetchLevel.STATEMENT,
                                bankConnection = "foo"
                            ),
                            accountId = "foo"
                        )
                        delay(1000L)
                    }
                }
            }
        }
    }

    // HTTP requests loop, to measure perf. via TOP(1)
    @Ignore // because no assert takes place.
    @Test
    fun plainSandboxReqLoop() {
        withTestDatabase {
            prepSandboxDb()
            testApplication {
                application(sandboxApp)
                while (true) {
                    // This brings the CPU to > 10%
                    /*client.get("/demobanks/default/access-api/accounts/foo") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        basicAuth("foo", "foo")
                    }*/
                    // This brings the CPU between 3 and 10%
                    /*client.get("/demobanks/default/integration-api/config") {
                        expectSuccess = true
                        contentType(ContentType.Application.Json)
                        // This caused 3 to 9% CPU => did not cause more usage.
                        // basicAuth("foo", "foo")
                    }*/
                    // Between 2 and 3% CPU.
                    client.get("/")
                    delay(1000L)
                }
            }
        }
    }
}

// This class investigates two ways of scheduling, regardless of the one used by Nexus.
class PlainJavaScheduling {
    val instanceTimer = Timer()
    // Below 5% CPU time.
    private fun loopWithJavaTimer() {
        println("with Java Timer " +
                "doing at ${System.currentTimeMillis() / 1000}.."
        ) // uncertain time goes by.
        instanceTimer.schedule(
            delay = 1200,
            action = { loopWithJavaTimer() }
        )
    }
    // Below 5% CPU time.
    private suspend fun loopWithWhileTrue() {
        val client = HttpClient()
        while (true) {
            println("With while-true " +
                    "doing at ${System.currentTimeMillis() / 1000}.."
            ) // uncertain time goes by.
            client.get("https://exchange.demo.taler.net/wrong") {
                basicAuth("foo", "foo")
            }
            delay(1000)
        }
    }
    @Ignore // due to no assert.
    @Test
    fun javaTimerLoop() {
        loopWithJavaTimer()
        runBlocking { delay(timeMillis = 30000) }
    }
    @Ignore // due to no assert.
    @Test
    fun whileTrueLoop() {
        runBlocking {
            loopWithWhileTrue()
        }
    }
}