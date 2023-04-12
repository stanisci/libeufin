import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp

/**
 * This class tests the API offered by Nexus,
 * documented here: https://docs.taler.net/libeufin/api-nexus.html
 */
class NexusApiTest {
    // Testing long-polling on GET /transactions
    @Test
    fun getTransactions() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                /**
                 * Requesting /transactions with long polling, and assert that
                 * the response arrives _after_ the unblocking INSERT into the
                 * database.
                 */
                val longPollMs = 5000
                runBlocking {
                    val requestJob = async {
                        client.get("/bank-accounts/foo/transactions?long_poll_ms=$longPollMs") {
                            basicAuth("foo", "foo")
                            contentType(ContentType.Application.Json)
                        }
                    }
                    /**
                     * The following delay ensures that the payment below
                     * gets inserted after the client has issued the long
                     * polled request above (and it is therefore waiting)
                     */
                    delay(2000)
                    // Ensures that the request is active _before_ the
                    // upcoming payment.  This ensures that the request
                    // didn't find already another payment in the database.
                    requestJob.ensureActive()
                    newNexusBankTransaction(
                        currency = "TESTKUDOS",
                        value = "2",
                        subject = "first"
                    )
                    val R = requestJob.await()
                    // Ensures that the request did NOT wait all the timeout
                    assert((R.responseTime.timestamp - R.requestTime.timestamp) < longPollMs)
                    val body = jacksonObjectMapper().readTree(R.bodyAsText())
                    // Ensures that the unblocking payment exists in the response.
                    val tx = body.get("transactions")
                    assert(tx.isArray && tx.size() == 1)
                }
            }
        }
    }
    // Testing basic operations on facades.
    @Test
    fun facades() {
        // Deletes the facade (created previously by MakeEnv.kt)
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                client.delete("/facades/foo-facade") {
                    basicAuth("foo", "foo")
                    expectSuccess = true
                }
            }
        }
    }

    // Testing the creation of scheduled tasks.
    @Test
    fun schedule() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                // POSTing omitted 'params', to test whether Nexus
                // expects it as 'null' for a 'submit' task.
                client.post("/bank-accounts/foo/schedule") {
                    contentType(ContentType.Application.Json)
                    expectSuccess = true
                    basicAuth("foo", "foo")
                    // NOTE: current API doesn't allow to omit the 'params' field.
                    setBody("""{
                        "name": "send-payments",
                        "cronspec": "* * *",
                        "type": "submit",
                        "params": null
                    }""".trimIndent())
                }
            }
        }
    }
}