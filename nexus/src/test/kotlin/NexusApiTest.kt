import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.netty.handler.codec.http.HttpResponseStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import tech.libeufin.nexus.PaymentInitiationEntity
import tech.libeufin.nexus.bankaccount.ingestBankMessagesIntoAccount
import tech.libeufin.nexus.getConnectionPlugin
import tech.libeufin.nexus.iso20022.ingestCamtMessageIntoAccount
import tech.libeufin.nexus.server.*
import tech.libeufin.sandbox.BankAccountTransactionEntity
import tech.libeufin.sandbox.BankAccountTransactionsTable
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.sandbox.wireTransfer

/**
 * This class tests the API offered by Nexus,
 * documented here: https://docs.taler.net/libeufin/api-nexus.html
 */
class NexusApiTest {
    private val jMapper = ObjectMapper()
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
    @Test
    fun facadeIdempotence() {
        val facadeData = """{
          "name": "foo-facade",
          "type": "taler-wire-gateway",
          "config": {
            "bankAccount": "foo",
            "bankConnection": "foo",
            "reserveTransferLevel": "report",
            "currency": "TESTKUDOS"
          }
        }""".trimIndent()
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                client.post("/facades") {
                    expectSuccess = true
                    basicAuth("foo", "foo")
                    contentType(ContentType.Application.Json)
                    setBody(facadeData)
                }
                // Changing one detail, and expecting 409 Conflict.
                var resp = client.post("/facades") {
                    expectSuccess = false
                    basicAuth("foo", "foo")
                    contentType(ContentType.Application.Json)
                    setBody(facadeData.replace(
                        "taler-wire-gateway",
                        "anastasis"
                    ))
                }
                assert(resp.status.value == HttpStatusCode.Conflict.value)
                // Changing a value deeper in the request object.
                resp = client.post("/facades") {
                    expectSuccess = false
                    basicAuth("foo", "foo")
                    contentType(ContentType.Application.Json)
                    setBody(facadeData.replace(
                        "report",
                        "statement"
                    ))
                }
                assert(resp.status.value == HttpStatusCode.Conflict.value)
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
    /**
     * Testing the idempotence of payment submissions.  That
     * helps Sandbox not to create multiple payment initiations
     * in case it fails at keeping track of what it submitted
     * already.
     */
    @Test
    fun paymentInitIdempotence() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                // Check no pay. ini. exist.
                transaction { PaymentInitiationEntity.all().count() == 0L }
                // Create one.
                fun f(futureThis: HttpRequestBuilder, subject: String = "idempotence pay. init. test") {
                    futureThis.basicAuth("foo", "foo")
                    futureThis.expectSuccess = true
                    futureThis.contentType(ContentType.Application.Json)
                    futureThis.setBody("""
                        {"iban": "TESTIBAN",
                         "bic": "SANDBOXX",
                         "name": "TEST NAME",
                         "amount": "TESTKUDOS:3",
                         "subject": "$subject",
                         "uid": "salt"
                         }
                    """.trimIndent())
                }
                val R = client.post("/bank-accounts/foo/payment-initiations") { f(this) }
                println(jMapper.readTree(R.bodyAsText()).get("uuid"))
                // Submit again
                client.post("/bank-accounts/foo/payment-initiations") { f(this) }
                // Checking that Nexus serves it.
                client.get("/bank-accounts/foo/payment-initiations/1") {
                    basicAuth("foo", "foo")
                    expectSuccess = true
                }
                // Checking that the database has only one, despite the double submission.
                transaction {
                    assert(PaymentInitiationEntity.all().count() == 1L)
                }
                /**
                 * Causing a conflict by changing one payment detail
                 * (the subject in this case) but not the "uid".
                 */
                val maybeConflict = client.post("/bank-accounts/foo/payment-initiations") {
                    f(this, "different-subject")
                    expectSuccess = false
                }
                assert(maybeConflict.status.value == HttpStatusCode.Conflict.value)
            }
        }
    }
    @Test
    fun timeRangeFetch() {
        withTestDatabase {
            prepSandboxDb()
            prepNexusDb()
            val ref = wireTransfer(
                "admin",
                "foo",
                subject = "past payment",
                amount = "TESTKUDOS:30"
                )
            transaction {
                BankAccountTransactionEntity.find {
                    BankAccountTransactionsTable.accountServicerReference eq ref
                }.first().date = 1577833200000L // Jan, 1st, 2020
            }
            testApplication {
                application(sandboxApp)
                val conn = getConnectionPlugin("ebics")

                // Asking a time range where the one payment is expected to exist
                conn.fetchTransactions(
                    fetchSpec = FetchSpecTimeRangeJson(
                        FetchLevel.REPORT,
                        start = "2019-12-31",
                        end = "2020-01-02",
                        bankConnection = null
                    ),
                    accountId = "foo",
                    bankConnectionId = "foo",
                    client = client
                )
                val res = ingestBankMessagesIntoAccount("foo", "foo")
                assert(res.newTransactions == 1)
                // Asking a time range where the one payment is NOT expected to exist
                conn.fetchTransactions(
                    fetchSpec = FetchSpecTimeRangeJson(
                        FetchLevel.REPORT,
                        start = "2019-10-31",
                        end = "2020-11-30",
                        bankConnection = null
                    ),
                    accountId = "foo",
                    bankConnectionId = "foo",
                    client = client
                )
                val resNoData = ingestBankMessagesIntoAccount("foo", "foo")
                assert(resNoData.downloadedTransactions == 0)
                assert(resNoData.newTransactions == 0)
            }
        }
    }
}