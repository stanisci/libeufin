import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Ignore
import org.junit.Test
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.bankaccount.submitAllPaymentInitiations
import tech.libeufin.nexus.ingestFacadeTransactions
import tech.libeufin.nexus.maybeTalerRefunds
import tech.libeufin.nexus.server.*
import tech.libeufin.nexus.talerFilter
import tech.libeufin.sandbox.sandboxApp
import tech.libeufin.sandbox.wireTransfer
import tech.libeufin.util.NotificationsChannelDomains

// This class tests the features related to the Taler facade.
class TalerTest {
    val mapper = ObjectMapper()

    // Checking that a call to POST /transfer results in
    // an outgoing payment in GET /history/outgoing.
    @Test
    fun historyOutgoingTest() {
        withNexusAndSandboxUser {
            testApplication {
                application(nexusApp)
                client.post("/facades/taler/taler-wire-gateway/transfer") {
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo") // exchange's credentials
                    expectSuccess = true
                    setBody("""
                        { "request_uid": "twg_transfer_0",
                          "amount": "TESTKUDOS:3",
                          "exchange_base_url": "http://exchange.example.com/",
                          "wtid": "T0",
                          "credit_account": "payto://iban/${BAR_USER_IBAN}?receiver-name=Bar"
                        
                        }
                    """.trimIndent())
                }
            }
            /* The EBICS layer sends the payment instruction to the bank here.
             *  and the reconciliation mechanism in Nexus should detect that one
             *  outgoing payment was indeed the one instructed via the TWG.  The
             *  reconciliation will make the outgoing payment visible via /history/outgoing.
             *  The following block achieve this by starting Sandbox and sending all
             *  the prepared payments to it.
             */
            testApplication {
                application(sandboxApp)
                submitAllPaymentInitiations(client, "foo")
                /* Now downloads transactions from the bank, where the payment
                   submitted in the previous block is expected to appear as outgoing.
                 */
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = FetchLevel.REPORT,
                        "foo"
                    ),
                    "foo"
                )
            }
            /**
             * Now Nexus starts again, in order to serve /history/outgoing
             * along the TWG.
             */
            testApplication {
                application(nexusApp)
                val r = client.get("/facades/taler/taler-wire-gateway/history/outgoing?delta=5") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo")
                }
                val j = mapper.readTree(r.readBytes())
                val wtidFromTwg = j.get("outgoing_transactions").get(0).get("wtid").asText()
                assert(wtidFromTwg == "T0")
            }
        }
    }

    // Checking that a correct wire transfer (with Taler-compatible subject)
    // is responded by the Taler facade.
    @Test
    fun historyIncomingTest() {
        val reservePub = "GX5H5RME193FDRCM1HZKERXXQ2K21KH7788CKQM8X6MYKYRBP8F0"
        withNexusAndSandboxUser {
            testApplication {
                application(nexusApp)
                runBlocking {
                    launch {
                        val r = client.get("/facades/taler/taler-wire-gateway/history/incoming?delta=5&start=0&long_poll_ms=3000") {
                            expectSuccess = false
                            contentType(ContentType.Application.Json)
                            basicAuth("foo", "foo")
                        }
                        println("maybe response body: ${r.bodyAsText()}")
                        assert(r.status.value == HttpStatusCode.OK.value)
                        val j = mapper.readTree(r.readBytes())
                        val reservePubFromTwg = j.get("incoming_transactions").get(0).get("reserve_pub").asText()
                        assert(reservePubFromTwg == reservePub)
                    }
                    newNexusBankTransaction(
                        "KUDOS",
                        "10",
                        reservePub
                    )
                    ingestFacadeTransactions(
                        "foo", // bank account local to Nexus.
                        "taler-wire-gateway",
                        ::talerFilter,
                        ::maybeTalerRefunds
                    )
                }
            }
        }
    }

    @Ignore // Ignoring because no assert takes place.
    @Test // Triggering a refund because of a duplicate reserve pub.
    fun refundTest() {
        withNexusAndSandboxUser {
            // Creating a Taler facade for the user 'foo'.
            testApplication {
                application(nexusApp)
                client.post("/facades") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    basicAuth("foo", "foo")
                    setBody("""
                        { "name":"foo-facade",
                          "type":"taler-wire-gateway",
                          "config": {
                            "bankAccount":"foo",
                            "bankConnection":"foo",
                            "currency":"TESTKUDOS",
                            "reserveTransferLevel":"report"
                          }
                    }""".trimIndent()
                    )
                }
            }
            wireTransfer(
                "bar",
                "foo",
                demobank = "default",
                "5WFM8PXN7Y315RVZFJ280299B94W1HR1AAHH6XNDYEJBC0T3E5N0",
                "TESTKUDOS:3"
            )
            testApplication {
                application(sandboxApp)
                // Nexus downloads the fresh transaction.
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = FetchLevel.REPORT,
                        "foo"
                    ),
                    "foo"
                )
            }
            wireTransfer(
                "bar",
                "foo",
                demobank = "default",
                "5WFM8PXN7Y315RVZFJ280299B94W1HR1AAHH6XNDYEJBC0T3E5N0",
                "TESTKUDOS:3"
            )
            testApplication {
                application(sandboxApp)
                // Nexus downloads the new transaction, having a duplicate subject.
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = FetchLevel.REPORT,
                        "foo"
                    ),
                    "foo"
                )
            }
        }
    }
}