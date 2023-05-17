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
import tech.libeufin.util.getIban

// This class tests the features related to the Taler facade.
class TalerTest {
    private val mapper = ObjectMapper()

    @Test
    fun historyOutgoingTestEbics() {
        historyOutgoingTest("foo")
    }
    @Test
    fun historyOutgoingTestXLibeufinBank() {
        historyOutgoingTest("bar")
    }

    // Checking that a call to POST /transfer results in
    // an outgoing payment in GET /history/outgoing.
    fun historyOutgoingTest(testedAccount: String) {
        withNexusAndSandboxUser {
            testApplication {
                application(nexusApp)
                client.post("/facades/$testedAccount-facade/taler-wire-gateway/transfer") {
                    contentType(ContentType.Application.Json)
                    basicAuth(testedAccount, testedAccount) // exchange's credentials
                    expectSuccess = true
                    setBody("""
                        { "request_uid": "twg_transfer_0",
                          "amount": "TESTKUDOS:3",
                          "exchange_base_url": "http://exchange.example.com/",
                          "wtid": "T0",
                          "credit_account": "payto://iban/${BANK_IBAN}?receiver-name=Not-Used"
                        }
                    """.trimIndent())
                }
            }
            /* The bank connection sends the payment instruction to the bank here.
             * and the reconciliation mechanism in Nexus should detect that one
             * outgoing payment was indeed the one instructed via the TWG.  The
             * reconciliation will make the outgoing payment visible via /history/outgoing.
             * The following block achieve this by starting Sandbox and sending all
             * the prepared payments to it.
             */
            testApplication {
                application(sandboxApp)
                submitAllPaymentInitiations(client, testedAccount)
                /* Now downloads transactions from the bank, where the payment
                   submitted in the previous block is expected to appear as outgoing.
                 */
                fetchBankAccountTransactions(
                    client,
                    fetchSpec = FetchSpecAllJson(
                        level = if (testedAccount == "bar") FetchLevel.STATEMENT else FetchLevel.REPORT,
                        bankConnection = testedAccount
                    ),
                    accountId = testedAccount
                )
            }
            /**
             * Now Nexus starts again, in order to serve /history/outgoing
             * along the TWG.
             */
            testApplication {
                application(nexusApp)
                val r = client.get("/facades/$testedAccount-facade/taler-wire-gateway/history/outgoing?delta=5") {
                    expectSuccess = true
                    contentType(ContentType.Application.Json)
                    basicAuth(testedAccount, testedAccount)
                }
                assert(r.status.value == HttpStatusCode.OK.value)
                val j = mapper.readTree(r.readBytes())
                val wtidFromTwg = j.get("outgoing_transactions").get(0).get("wtid").asText()
                assert(wtidFromTwg == "T0")
            }
        }
    }

    // Tests that incoming Taler txs arrive via EBICS.
    @Test
    fun historyIncomingTestEbics() {
        historyIncomingTest(
            testedAccount = "foo",
            connType = BankConnectionType.EBICS
        )
    }

    // Tests that incoming Taler txs arrive via x-libeufin-bank.
    @Test
    fun historyIncomingTestXLibeufinBank() {
        historyIncomingTest(
            testedAccount = "bar",
            connType = BankConnectionType.X_LIBEUFIN_BANK
        )
    }

    // Tests that even if one call is long-polling, other calls respond.
    @Test
    fun servingTest() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                val currentTime = System.currentTimeMillis()
                runBlocking {
                    launch {
                        val r = client.get("/facades/foo-facade/taler-wire-gateway/history/incoming?delta=5&start=0&long_poll_ms=5000") {
                            expectSuccess = true
                            contentType(ContentType.Application.Json)
                            basicAuth("foo", "foo") // user & pw always equal.
                        }
                        assert(r.status.value == HttpStatusCode.NoContent.value)
                    }
                    val R = client.get("/") {
                        expectSuccess = true
                    }
                    val latestTime = System.currentTimeMillis()
                    // Checks that the call didn't hang for the whole long_poll_ms.
                    assert(R.status.value == HttpStatusCode.OK.value
                            && (latestTime - currentTime) < 2000
                    )
                }
            }
        }
    }

    // Downloads Taler txs via the default connection of 'testedAccount'.
    // This allows to test the Taler logic on different connection types.
    private fun historyIncomingTest(testedAccount: String, connType: BankConnectionType) {
        val reservePub = "GX5H5RME193FDRCM1HZKERXXQ2K21KH7788CKQM8X6MYKYRBP8F0"
        withNexusAndSandboxUser {
            testApplication {
                application(nexusApp)
                runBlocking {
                    /**
                     * This block issues the request by long-polling and
                     * lets the execution proceed where the actions to unblock
                     * the polling are taken.
                     */
                    launch {
                        val r = client.get("/facades/${testedAccount}-facade/taler-wire-gateway/history/incoming?delta=5&start=0&long_poll_ms=30000") {
                            expectSuccess = true
                            contentType(ContentType.Application.Json)
                            basicAuth(testedAccount, testedAccount) // user & pw always equal.
                        }
                        assertWithPrint(
                            r.status.value == HttpStatusCode.OK.value,
                            "Long-polling history had status: ${r.status.value} and" +
                                    " body: ${r.bodyAsText()}"
                        )
                        val j = mapper.readTree(r.readBytes())
                        val reservePubFromTwg = j.get("incoming_transactions").get(0).get("reserve_pub").asText()
                        assert(reservePubFromTwg == reservePub)
                    }
                    launch {
                        delay(500)
                        newNexusBankTransaction(
                            currency = "KUDOS",
                            value = "10",
                            subject = reservePub,
                            creditorAcct = testedAccount,
                            connType = connType
                        )
                        ingestFacadeTransactions(
                            bankAccountId = testedAccount, // bank account local to Nexus.
                            facadeType = NexusFacadeType.TALER,
                            incomingFilterCb = ::talerFilter,
                            refundCb = ::maybeTalerRefunds
                        )
                    }
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